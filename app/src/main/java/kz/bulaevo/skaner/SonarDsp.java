package kz.bulaevo.skaner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Matched-filter sonar processing.
 *
 * AudioRecord starts before AudioTrack playback, so the direct sound does not
 * necessarily appear at sample zero. We first locate the earliest strong
 * direct-path peak inside a bounded playback-latency gate, then measure
 * later peaks relative to that origin. With DECIMATION=2, the decimated lag
 * index multiplied by c/sampleRate is the round-trip-corrected distance.
 */
final class SonarDsp {
    private static final double SPEED_OF_SOUND = 343.0;
    private static final int DECIMATION = 2;
    private static final double DIRECT_LATENCY_WINDOW_SECONDS = 0.080;
    private static final double MIN_RANGE_METERS = 0.24;
    private static final double MAX_RANGE_METERS = 6.5;

    static final class Echo {
        final float distanceMeters;
        final float strength;
        Echo(float distanceMeters, float strength) {
            this.distanceMeters = distanceMeters;
            this.strength = strength;
        }
    }

    static final class Analysis {
        final Echo[] echoes;
        final float directStrength;
        Analysis(Echo[] echoes, float directStrength) {
            this.echoes = echoes;
            this.directStrength = directStrength;
        }
    }

    private SonarDsp() { }

    static short[] makeChirp(int sampleRate, double startHz, double endHz, int durationMs) {
        int count = Math.max(256, sampleRate * durationMs / 1000);
        short[] result = new short[count];
        double phase = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double frequency = startHz + (endHz - startHz) * t;
            phase += 2.0 * Math.PI * frequency / sampleRate;
            double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (count - 1));
            result[i] = (short) (Math.sin(phase) * window * 24000.0);
        }
        return result;
    }

    static Echo[] detect(short[] signal, short[] reference, int sampleRate) {
        return analyze(signal, reference, sampleRate).echoes;
    }

    static Analysis analyze(short[] signal, short[] reference, int sampleRate) {
        if (signal == null || reference == null || sampleRate <= 0
                || signal.length < reference.length * 2 || reference.length < 128) {
            return new Analysis(new Echo[0], 0f);
        }

        int templateLength = reference.length / DECIMATION;
        double[] template = new double[templateLength];
        double templateEnergy = 0.0;
        for (int i = 0; i < templateLength; i++) {
            template[i] = reference[i * DECIMATION] / 32768.0;
            templateEnergy += template[i] * template[i];
        }
        if (templateEnergy < 1.0e-6) return new Analysis(new Echo[0], 0f);

        int decimatedLength = signal.length / DECIMATION;
        int candidateCount = decimatedLength - templateLength;
        if (candidateCount < 16) return new Analysis(new Echo[0], 0f);

        // The recording clock and speaker playback clock are not sample-aligned.
        // Allow hardware buffering latency, but do not let room echoes define zero.
        int directSearchLimit = Math.min(candidateCount - 2,
                Math.max(8, (int) Math.ceil(sampleRate
                        * DIRECT_LATENCY_WINDOW_SECONDS / DECIMATION)));
        int maxRangeLag = (int) Math.ceil(sampleRate * MAX_RANGE_METERS / SPEED_OF_SOUND);
        int lastSearch = Math.min(candidateCount - 2, directSearchLimit + maxRangeLag);
        float[] scores = new float[lastSearch + 2];
        double[] energies = new double[lastSearch + 2];

        for (int candidate = 0; candidate <= lastSearch + 1; candidate++) {
            int start = candidate;
            double dot = 0.0;
            double energy = 0.0;
            for (int j = 0; j < templateLength; j++) {
                double value = signal[(start + j) * DECIMATION] / 32768.0;
                dot += value * template[j];
                energy += value * value;
            }
            scores[candidate] = (float) (Math.abs(dot)
                    / Math.sqrt(Math.max(1.0e-12, energy * templateEnergy)));
            energies[candidate] = energy / templateLength;
        }

        float strongestDirect = 0f;
        for (int i = 0; i <= directSearchLimit; i++) {
            strongestDirect = Math.max(strongestDirect, scores[i]);
        }
        if (strongestDirect < 0.10f) {
            return new Analysis(new Echo[0], strongestDirect);
        }

        // Use the first credible local maximum, not the strongest arbitrary
        // peak. The latter may be a nearby wall reflection and shifts every range.
        float directFloor = Math.max(0.12f, strongestDirect * 0.58f);
        int directIndex = -1;
        for (int i = 1; i < directSearchLimit; i++) {
            if (scores[i] >= directFloor && scores[i] > scores[i - 1]
                    && scores[i] >= scores[i + 1]) {
                directIndex = i;
                break;
            }
        }
        if (directIndex < 0) {
            for (int i = 0; i <= directSearchLimit; i++) {
                if (scores[i] > scores[Math.max(0, directIndex)]) directIndex = i;
            }
        }
        if (directIndex < 0 || scores[directIndex] < 0.10f) {
            return new Analysis(new Echo[0], strongestDirect);
        }

        int first = Math.max(directIndex + 1,
                directIndex + (int) Math.ceil(sampleRate * MIN_RANGE_METERS / SPEED_OF_SOUND));
        int last = Math.min(lastSearch, directIndex + maxRangeLag);
        if (last <= first + 2) return new Analysis(new Echo[0], strongestDirect);

        float[] noiseScores = new float[last - first + 1];
        for (int i = first; i <= last; i++) noiseScores[i - first] = scores[i];
        Arrays.sort(noiseScores);
        float p99 = noiseScores[Math.min(noiseScores.length - 1,
                (int) Math.floor((noiseScores.length - 1) * 0.99))];
        float threshold = Math.max(0.18f, p99 + 0.025f);
        double directEnergy = Math.max(1.0e-12, energies[directIndex]);

        List<Echo> found = new ArrayList<>();
        for (int i = first + 1; i < last; i++) {
            float score = scores[i];
            if (score < threshold || score <= scores[i - 1] || score < scores[i + 1]) continue;
            // Normalized correlation alone can rate tiny noise as a good match.
            if (energies[i] < directEnergy * 0.00002) continue;

            double fraction = parabolicOffset(scores[i - 1], score, scores[i + 1]);
            double lag = (i + fraction) - directIndex;
            double range = lag * SPEED_OF_SOUND / sampleRate;
            if (range >= MIN_RANGE_METERS && range <= MAX_RANGE_METERS) {
                found.add(new Echo((float) range, score));
            }
        }

        Collections.sort(found, new Comparator<Echo>() {
            @Override public int compare(Echo a, Echo b) {
                return Float.compare(b.strength, a.strength);
            }
        });

        List<Echo> filtered = new ArrayList<>();
        for (Echo candidate : found) {
            boolean duplicate = false;
            for (Echo kept : filtered) {
                if (Math.abs(candidate.distanceMeters - kept.distanceMeters) < 0.14f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) filtered.add(candidate);
            if (filtered.size() == 3) break;
        }
        Collections.sort(filtered, new Comparator<Echo>() {
            @Override public int compare(Echo a, Echo b) {
                return Float.compare(a.distanceMeters, b.distanceMeters);
            }
        });
        return new Analysis(filtered.toArray(new Echo[0]), strongestDirect);
    }

    private static double parabolicOffset(float before, float peak, float after) {
        double denominator = before - 2.0 * peak + after;
        if (Math.abs(denominator) < 1.0e-9) return 0.0;
        return Math.max(-0.5, Math.min(0.5, 0.5 * (before - after) / denominator));
    }
}
