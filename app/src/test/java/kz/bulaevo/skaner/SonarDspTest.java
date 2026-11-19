package kz.bulaevo.skaner;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SonarDspTest {
    @Test
    public void matchedFilterFindsSyntheticOneMeterEcho() {
        int sampleRate = 48000;
        short[] chirp = SonarDsp.makeChirp(sampleRate, 16000, 19500, 24);
        short[] signal = new short[18000];
        int directAt = 1800;
        int echoDelay = (int) Math.round(sampleRate / 343.0);
        for (int i = 0; i < chirp.length; i++) {
            signal[directAt + i] = (short) (chirp[i] * 0.75);
            int echoAt = directAt + echoDelay + i;
            signal[echoAt] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, signal[echoAt] + (int) (chirp[i] * 0.30)));
        }
        SonarDsp.Echo[] echoes = SonarDsp.detect(signal, chirp, sampleRate);
        assertTrue("expected a detected reflection", echoes.length > 0);
        assertTrue("expected distance close to 1 m: " + echoes[0].distanceMeters,
                Math.abs(echoes[0].distanceMeters - 1.0) < 0.15);
    }

    @Test
    public void silenceDoesNotCreateEchoes() {
        int sampleRate = 48000;
        short[] chirp = SonarDsp.makeChirp(sampleRate, 16000, 19500, 24);
        assertTrue(SonarDsp.detect(new short[16000], chirp, sampleRate).length == 0);
    }
}
