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
    private int startHz = 15000;
    private int endHz = 19000;

    SonarEngine(Listener listener) {
        this.listener = listener;
    }

    synchronized void start(boolean highFrequency) {
        if (running) return;
        startHz = highFrequency ? 17000 : 15000;
        endHz = highFrequency ? 19800 : 19000;
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
            chirp = SonarDsp.makeChirp(SAMPLE_RATE, startHz, endHz, 4);
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
            player.setVolume(0.92f);

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
            short[] quietGap = new short[2400];

            while (running) {
                long marker = writeIndex.get();
                int written = player.write(chirp, 0, chirp.length, AudioTrack.WRITE_BLOCKING);
                if (written < 0) throw new IllegalStateException("Ошибка вывода звука: " + written);
                player.write(quietGap, 0, quietGap.length, AudioTrack.WRITE_BLOCKING);
                Thread.sleep(175);
                short[] capture = snapshot(marker);
                SonarDsp.Analysis result = SonarDsp.analyze(capture, chirp, SAMPLE_RATE);
                listener.onMeasurement(result.echoes, result.directStrength);
                if (result.directStrength < 0.06f) {
                    listener.onStatus("Слабый сигнал · проверьте громкость и положение");
                } else if (result.echoes.length == 0) {
                    listener.onStatus("Сигнал есть · отражения неразличимы");
                } else {
                    listener.onStatus("Обнаружено отражений: " + result.echoes.length);
                }
                Thread.sleep(45);
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
