package kz.bulaevo.skaner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SonarDspTest {
    @Test
    public void matchedFilterFindsEchoAfterPlaybackLatency() {
        int sampleRate = 48000;
        short[] chirp = SonarDsp.makeChirp(sampleRate, 16000, 19500, 4);
        short[] signal = new short[12000];
        int directAt = 1800; // Simulated AudioTrack/AudioRecord start offset: 37.5 ms.
        int echoDelay = (int) Math.round(2.0 * sampleRate / 343.0);
        addPulse(signal, chirp, directAt, 0.80);
        addPulse(signal, chirp, directAt + echoDelay, 0.30);

        SonarDsp.Analysis result = SonarDsp.analyze(signal, chirp, sampleRate);
        assertTrue("expected a detected reflection", result.echoes.length > 0);
        assertTrue("expected about 1 m: " + result.echoes[0].distanceMeters,
                Math.abs(result.echoes[0].distanceMeters - 1.0) < 0.10);
    }

    @Test
    public void multipleEchoesAreSeparatedByDistance() {
        int sampleRate = 48000;
        short[] chirp = SonarDsp.makeChirp(sampleRate, 16000, 19500, 4);
        short[] signal = new short[14000];
        int directAt = 1500;
        addPulse(signal, chirp, directAt, 0.85);
        addPulse(signal, chirp, directAt + (int) Math.round(2.0 * sampleRate / 343.0), 0.35);
        addPulse(signal, chirp, directAt + (int) Math.round(4.0 * sampleRate / 343.0), 0.22);

        SonarDsp.Echo[] echoes = SonarDsp.detect(signal, chirp, sampleRate);
        assertTrue("expected at least two separate reflectors", echoes.length >= 2);
        assertTrue(Math.abs(echoes[0].distanceMeters - 1.0) < 0.12);
        assertTrue(Math.abs(echoes[1].distanceMeters - 2.0) < 0.15);
    }

    @Test
    public void directPathAloneDoesNotBecomeAnObstacle() {
        int sampleRate = 48000;
        short[] chirp = SonarDsp.makeChirp(sampleRate, 16000, 19500, 4);
        short[] signal = new short[10000];
        addPulse(signal, chirp, 1250, 0.85);
        assertEquals(0, SonarDsp.detect(signal, chirp, sampleRate).length);
    }

    @Test
    public void twoEchoesAreResolvedAtCorrectPhysicalDistances() {
        int sampleRate = 48000;
        short[] chirp = SonarDsp.makeChirp(sampleRate, 16000, 19500, 4);
        short[] signal = new short[24000];
        int directAt = 1400;
        int nearDelay = (int) Math.round(2.0 * 0.65 * sampleRate / 343.0);
        int farDelay = (int) Math.round(2.0 * 2.10 * sampleRate / 343.0);
        for (int i = 0; i < chirp.length; i++) {
            signal[directAt + i] = (short) (chirp[i] * 0.8);
            signal[directAt + nearDelay + i] = (short) (chirp[i] * 0.30);
            signal[directAt + farDelay + i] = (short) (chirp[i] * 0.24);
        }
        SonarDsp.Echo[] echoes = SonarDsp.detect(signal, chirp, sampleRate);
        assertTrue("expected near and far echoes", echoes.length >= 2);
        assertTrue(Math.abs(echoes[0].distanceMeters - 0.65) < 0.12);
        assertTrue(Math.abs(echoes[1].distanceMeters - 2.10) < 0.12);
    }

    @Test
    public void silenceDoesNotCreateEchoes() {
        int sampleRate = 48000;
        short[] chirp = SonarDsp.makeChirp(sampleRate, 16000, 19500, 4);
        assertEquals(0, SonarDsp.detect(new short[16000], chirp, sampleRate).length);
    }

    @Test
    public void longRangeModeFindsEchoAtEightMeters() {
        int sampleRate = 48000;
        short[] chirp = SonarDsp.makeChirp(sampleRate, 3500, 7500, 8);
        short[] signal = new short[18000];
        int directAt = 1500;
        int echoDelay = (int) Math.round(2.0 * 8.0 * sampleRate / 343.0);
        addPulse(signal, chirp, directAt, 0.82);
        addPulse(signal, chirp, directAt + echoDelay, 0.24);

        SonarDsp.Analysis result = SonarDsp.analyze(signal, chirp, sampleRate, 10.0f);
        assertTrue("expected a long-range reflection", result.echoes.length > 0);
        assertTrue("expected about 8 m: " + result.echoes[0].distanceMeters,
                Math.abs(result.echoes[0].distanceMeters - 8.0) < 0.15);
    }

    private static void addPulse(short[] signal, short[] pulse, int offset, double gain) {
        for (int i = 0; i < pulse.length && offset + i < signal.length; i++) {
            int mixed = signal[offset + i] + (int) Math.round(pulse[i] * gain);
            signal[offset + i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, mixed));
        }
    }
}
