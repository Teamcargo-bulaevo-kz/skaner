package kz.bulaevo.skaner;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;

import java.util.concurrent.atomic.AtomicLong;

/** Full-duplex chirp transmitter and continuous microphone capture. */
final class SonarEngine {
    interface Listener {
        void onStatus(String status);
        void onMeasurement(SonarDsp.Echo[] echoes, float signalStrength);
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int RING_SIZE = 32768;
    private final Listener listener;
    private final AtomicLong writeIndex = new AtomicLong();
    private final short[] ring = new short[RING_SIZE];

    private volatile boolean running;
    private Thread worker;
    private Thread captureWorker;
    private AudioRecord recorder;
    private AudioTrack player;
    private AutomaticGainControl agc;
    private NoiseSuppressor noiseSuppressor;
    private AcousticEchoCanceler echoCanceler;
    private short[] chirp;
    private int startHz = 5000;
    private int endHz = 9500;
    private float maxRangeMeters = 2.0f;
    private final float[] recentNearest = {-1f, -1f, -1f};
    private int recentIndex;
    private long lastBeepAt;

    SonarEngine(Listener listener) {
        this.listener = listener;
    }

    synchronized void start(boolean longRangeMode) {
        if (running) return;
        maxRangeMeters = longRangeMode ? 10.0f : 2.0f;
        startHz = longRangeMode ? 3500 : 5000;
        endHz = longRangeMode ? 7500 : 9500;
        running = true;
        worker = new Thread(this::runSonar, "Skaner-Sonar");
        worker.start();
    }

    synchronized void stop() {
        running = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) { }
        }
        if (player != null) {
            try { player.pause(); player.flush(); } catch (Exception ignored) { }
        }
        if (worker != null) worker.interrupt();
        if (captureWorker != null) captureWorker.interrupt();
        releaseAudio();
    }

    private void runSonar() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try {
            chirp = SonarDsp.makeChirp(SAMPLE_RATE, startHz, endHz, 8);
            int minRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minRecord <= 0 || minTrack <= 0) {
                throw new IllegalStateException("Телефон не поддерживает аудио 48 кГц");
            }

            int source = BuildVersion.unprocessedSource();
            recorder = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minRecord * 2, SAMPLE_RATE / 2));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minRecord * 2, SAMPLE_RATE / 2));
            }
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Не удалось открыть микрофон 48 кГц");
            }

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            player = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minTrack * 2, chirp.length * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (player.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Не удалось открыть динамик");
            }
            player.setVolume(0.55f);
            lastBeepAt = 0L;
            recentNearest[0] = recentNearest[1] = recentNearest[2] = -1f;
            recentIndex = 0;

            int sessionId = recorder.getAudioSessionId();
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId);
                if (agc != null) agc.setEnabled(false);
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(false);
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(false);
            }

            writeIndex.set(0);
            recorder.startRecording();
            player.play();
            captureWorker = new Thread(this::captureLoop, "Skaner-Microphone");
            captureWorker.start();
            listener.onStatus("Идёт акустическое сканирование");
            short[] quietGap = new short[6000]; // 125 ms; gives late room echoes time to reach the mic
            short[] tick = makeGeigerClick(SAMPLE_RATE, 6);
            short[] tickTail = new short[960];

            while (running) {
                long marker = writeIndex.get();
                int written = player.write(chirp, 0, chirp.length, AudioTrack.WRITE_BLOCKING);
                if (written < 0) throw new IllegalStateException("Ошибка вывода звука: " + written);
                player.write(quietGap, 0, quietGap.length, AudioTrack.WRITE_BLOCKING);
                if (!awaitCapture(marker, SAMPLE_RATE * 150 / 1000)) continue;
                short[] capture = snapshot(marker);
                SonarDsp.Analysis result = SonarDsp.analyze(capture, chirp, SAMPLE_RATE, maxRangeMeters);
                listener.onMeasurement(result.echoes, result.directStrength);
                float stableNearest = updateStableNearest(result.echoes);
                if (stableNearest > 0f) playGeigerTickIfDue(stableNearest, tick, tickTail);
                if (result.directStrength < 0.06f) {
                    listener.onStatus("Слабый сигнал · проверьте громкость и положение");
                } else if (result.echoes.length == 0) {
                    listener.onStatus("Сигнал есть · отражения неразличимы");
                } else {
                    listener.onStatus("Обнаружено отражений: " + result.echoes.length);
                }
                Thread.sleep(28);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (running) listener.onError(error.getMessage() == null
                    ? "Не удалось запустить акустический радар" : error.getMessage());
        } finally {
            running = false;
            releaseAudio();
        }
    }


    private float updateStableNearest(SonarDsp.Echo[] echoes) {
        float nearest = -1f;
        if (echoes != null) {
            for (SonarDsp.Echo echo : echoes) {
                if (echo.strength >= 0.10f && (nearest < 0f || echo.distanceMeters < nearest)) {
                    nearest = echo.distanceMeters;
                }
            }
        }
        recentNearest[recentIndex] = nearest;
        recentIndex = (recentIndex + 1) % recentNearest.length;
        float best = -1f;
        for (float candidate : recentNearest) {
            if (candidate <= 0f) continue;
            int matches = 0;
            float sum = 0f;
            for (float other : recentNearest) {
                if (other > 0f && Math.abs(other - candidate) <= 0.30f) {
                    matches++;
                    sum += other;
                }
            }
            if (matches >= 2 && (best < 0f || sum / matches < best)) best = sum / matches;
        }
        return best;
    }

    private void playGeigerTickIfDue(float distance, short[] tick, short[] tail) {
        long now = android.os.SystemClock.elapsedRealtime();
        long interval;
        if (distance < 0.55f) interval = 120;
        else if (distance < 1.0f) interval = 210;
        else if (distance < 2.0f) interval = 340;
        else if (distance < 4.0f) interval = 560;
        else interval = 850;
        if (now - lastBeepAt < interval || player == null) return;
        lastBeepAt = now;
        try {
            player.setVolume(0.18f);
            player.write(tick, 0, tick.length, AudioTrack.WRITE_BLOCKING);
            player.write(tail, 0, tail.length, AudioTrack.WRITE_BLOCKING);
            player.setVolume(0.55f);
        } catch (Exception ignored) {
            try { if (player != null) player.setVolume(0.55f); } catch (Exception ignoredAgain) { }
        }
    }

    private static short[] makeGeigerClick(int sampleRate, int durationMs) {
        int count = sampleRate * durationMs / 1000;
        short[] click = new short[count];
        int state = 0x5A17C3;
        double smoothNoise = 0.0;
        for (int i = 0; i < count; i++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            double noise = ((state & 0x7fffffff) / (double) 0x3fffffff) - 1.0;
            smoothNoise = smoothNoise * 0.28 + noise * 0.72;
            double envelope = Math.min(1.0, Math.min(i / 18.0, (count - 1 - i) / 42.0));
            click[i] = (short) (smoothNoise * envelope * 7500.0);
        }
        return click;
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] block = new short[1024];
        while (running && recorder != null) {
            int count;
            try {
                count = recorder.read(block, 0, block.length, AudioRecord.READ_BLOCKING);
            } catch (Exception error) {
                break;
            }
            if (count > 0) {
                synchronized (ring) {
                    long index = writeIndex.get();
                    for (int i = 0; i < count; i++) ring[(int) ((index + i) % RING_SIZE)] = block[i];
                    writeIndex.set(index + count);
                }
            } else if (count < 0) {
                break;
            }
        }
    }


    private boolean awaitCapture(long marker, int requiredSamples) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + 240L;
        while (running && writeIndex.get() - marker < requiredSamples
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(2L);
        }
        return running && writeIndex.get() - marker >= requiredSamples;
    }

    private short[] snapshot(long marker) {
        synchronized (ring) {
            long end = writeIndex.get();
            long available = Math.max(0L, end - marker);
            int length = (int) Math.min(Math.min(available, RING_SIZE - 1), SAMPLE_RATE / 3);
            short[] result = new short[length];
            long start = Math.max(marker, end - length);
            for (int i = 0; i < length; i++) result[i] = ring[(int) ((start + i) % RING_SIZE)];
            return result;
        }
    }

    private synchronized void releaseAudio() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        try { if (player != null) player.release(); } catch (Exception ignored) { }
        try { if (agc != null) agc.release(); } catch (Exception ignored) { }
        try { if (noiseSuppressor != null) noiseSuppressor.release(); } catch (Exception ignored) { }
        try { if (echoCanceler != null) echoCanceler.release(); } catch (Exception ignored) { }
        recorder = null;
        player = null;
        agc = null;
        noiseSuppressor = null;
        echoCanceler = null;
    }

    private static final class BuildVersion {
        static int unprocessedSource() {
            return android.os.Build.VERSION.SDK_INT >= 24
                    ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.MIC;
        }
    }
}
