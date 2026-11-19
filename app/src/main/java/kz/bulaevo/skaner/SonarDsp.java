package kz.bulaevo.skaner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Matched-filter sonar processing. */
final class SonarDsp {
    private static final double SPEED_OF_SOUND = 343.0;
    private static final int DECIMATION = 2;

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
        double templateEnergy = 0.0;
        double[] template = new double[templateLength];
        for (int i = 0; i < templateLength; i++) {
            template[i] = reference[i * DECIMATION] / 32768.0;
            templateEnergy += template[i] * template[i];
        }
        if (templateEnergy < 0.000001) return new Analysis(new Echo[0], 0f);

        int decimatedLength = signal.length / DECIMATION;
        int candidateCount = decimatedLength - templateLength;
        if (candidateCount < 8) return new Analysis(new Echo[0], 0f);
        float[] scores = new float[candidateCount];
        int strongestIndex = -1;
        float strongest = 0f;
        int directSearchLimit = Math.min(candidateCount - 1,
                Math.max(8, (int) (sampleRate * 0.24 / DECIMATION / 2.0)));

        for (int candidate = 0; candidate <= directSearchLimit; candidate++) {
            int start = candidate;
            double dot = 0.0, energy = 0.0;
            for (int j = 0; j < templateLength; j++) {
                double value = signal[(start + j) * DECIMATION] / 32768.0;
                dot += value * template[j];
                energy += value * value;
            }
            float score = (float) (Math.abs(dot)
                    / Math.sqrt(Math.max(1.0e-12, energy * templateEnergy)));
            scores[candidate] = score;
            if (score > strongest) {
                strongest = score;
                strongestIndex = candidate;
            }
        }
        if (strongestIndex < 0 || strongest < 0.045f) {
            return new Analysis(new Echo[0], strongest);
        }

        int minDelta = Math.max(1, (int) Math.ceil(sampleRate * 0.24 / SPEED_OF_SOUND));
        int maxDelta = (int) (sampleRate * 6.5 / SPEED_OF_SOUND);
        int first = Math.max(strongestIndex + minDelta, 1);
        int last = Math.min(candidateCount - 2, strongestIndex + maxDelta);
        float threshold = Math.max(0.055f, strongest * 0.075f);
        List<Echo> found = new ArrayList<>();

        for (int i = first; i <= last; i++) {
            int start = i;
            double dot = 0.0, energy = 0.0;
            for (int j = 0; j < templateLength; j++) {
                double value = signal[(start + j) * DECIMATION] / 32768.0;
                dot += value * template[j];
                energy += value * value;
            }
            float score = (float) (Math.abs(dot)
                    / Math.sqrt(Math.max(1.0e-12, energy * templateEnergy)));
            scores[i] = score;
            if (score < threshold || score < scores[i - 1]) continue;

            int nextStart = i + 1;
            double nextDot = 0.0, nextEnergy = 0.0;
            for (int j = 0; j < templateLength; j++) {
                double value = signal[(nextStart + j) * DECIMATION] / 32768.0;
                nextDot += value * template[j];
                nextEnergy += value * value;
            }
            float nextScore = (float) (Math.abs(nextDot)
                    / Math.sqrt(Math.max(1.0e-12, nextEnergy * templateEnergy)));
            scores[i + 1] = nextScore;
            if (score < nextScore) continue;

            double range = (double) (i - strongestIndex) * SPEED_OF_SOUND / sampleRate;
            if (range >= 0.24 && range <= 6.5) found.add(new Echo((float) range, score));
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
                if (Math.abs(candidate.distanceMeters - kept.distanceMeters) < 0.38f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) filtered.add(candidate);
            if (filtered.size() == 5) break;
        }
        Collections.sort(filtered, new Comparator<Echo>() {
            @Override public int compare(Echo a, Echo b) {
                return Float.compare(a.distanceMeters, b.distanceMeters);
            }
        });
        return new Analysis(filtered.toArray(new Echo[0]), strongest);
    }
}
