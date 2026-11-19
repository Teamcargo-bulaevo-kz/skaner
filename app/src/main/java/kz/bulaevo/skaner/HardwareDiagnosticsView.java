package kz.bulaevo.skaner;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Size;
import android.graphics.SurfaceTexture;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Live, read-only hardware inventory for the Skaner diagnostics page. */
final class HardwareDiagnosticsView extends ScrollView implements SensorEventListener {
    private static final int BG = Color.rgb(7, 17, 15);
    private static final int TEXT = Color.rgb(229, 242, 236);
    private static final int MUTED = Color.rgb(136, 164, 151);
    private static final int GREEN = Color.rgb(105, 229, 181);

    private final Context context;
    private final SensorManager sensorManager;
    private final List<Sensor> sensors;
    private final TextView liveValues;
    private boolean listening;

    HardwareDiagnosticsView(Context context) {
        super(context);
        this.context = context;
        setFillViewport(true);
        setBackgroundColor(BG);
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        sensors = sensorManager == null
                ? new ArrayList<Sensor>() : sensorManager.getSensorList(Sensor.TYPE_ALL);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(22));
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addText(content, "ДИАГНОСТИКА ОБОРУДОВАНИЯ", 16, GREEN, true);
        addText(content, "Данные считываются непосредственно с Android. Отсутствующие датчики не эмулируются.", 12, MUTED, false);
        addText(content, "НАЙДЕНО ДАТЧИКОВ: " + sensors.size(), 13, TEXT, true);

        liveValues = addText(content, "ТЕКУЩИЕ ПОКАЗАНИЯ\nОткройте эту вкладку и слегка наклоните/поверните телефон.", 12, GREEN, false);
        liveValues.setPadding(0, dp(8), 0, dp(12));

        addText(content, "СПИСОК ДАТЧИКОВ", 14, GREEN, true);
        if (sensors.isEmpty()) {
            addText(content, "Android не сообщил приложению ни одного датчика.", 12, MUTED, false);
        } else {
            for (Sensor sensor : sensors) {
                String info = sensor.getName()
                        + "\nТип: " + sensor.getStringType() + " (" + sensor.getType() + ")"
                        + "\nПроизводитель: " + sensor.getVendor()
                        + " · версия " + sensor.getVersion()
                        + "\nДиапазон: " + fmt(sensor.getMaximumRange())
                        + " · разрешение: " + fmt(sensor.getResolution())
                        + "\nЭнергопотребление: " + fmt(sensor.getPower()) + " мА"
                        + "\nМин. интервал: " + sensor.getMinDelay() + " мкс"
                        + (Build.VERSION.SDK_INT >= 21 ? "\nМакс. задержка: " + sensor.getMaxDelay() + " мкс" : "")
                        + "\nWake-up: " + (sensor.isWakeUpSensor() ? "да" : "нет");
                addPanel(content, info);
            }
        }

        addText(content, "КАМЕРЫ", 14, GREEN, true);
        addText(content, cameraReport(), 12, TEXT, false);

        addText(content, "МИКРОФОНЫ И ДИНАМИКИ", 14, GREEN, true);
        addText(content, audioReport(), 12, TEXT, false);

        addText(content, "ПРОВЕРКА АУДИОФОРМАТОВ", 14, GREEN, true);
        addText(content, audioFormatReport(), 12, TEXT, false);

        addText(content, "ЧЕГО ТЕЛЕФОН НЕ УМЕЕТ САМ ПО СЕБЕ", 14, GREEN, true);
        addText(content,
                "Обычный Android Wi-Fi API не выдаёт CSI-данные радиоканала, необходимые для экспериментального Wi-Fi-радарa. Уровень сигнала Wi-Fi не позволяет надёжно строить карту или видеть человека за стеной. ИК-порт телефона — передатчик команд, а не дальномер. Для настоящей глубины в темноте нужен внешний ИК/ToF/тепловизионный модуль.",
                12, MUTED, false);
        addText(content, "Проверка микрофона с реальной записью выполняется отдельно при выданном разрешении. Эта страница ничего не записывает.", 11, MUTED, false);
    }

    void start() {
        if (listening || sensorManager == null) return;
        listening = true;
        for (Sensor sensor : sensors) {
            try { sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI); }
            catch (RuntimeException ignored) { }
        }
    }

    void stop() {
        if (!listening) return;
        listening = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        StringBuilder text = new StringBuilder("ТЕКУЩИЕ ПОКАЗАНИЯ\n");
        text.append(event.sensor.getName()).append("\n");
        String[] labels = {"X", "Y", "Z", "W"};
        for (int i = 0; i < event.values.length; i++) {
            if (i > 0) text.append("   ");
            text.append(i < labels.length ? labels[i] : "V" + i)
                    .append(": ").append(String.format(Locale.US, "%.4f", event.values[i]));
        }
        if (event.sensor.getType() == Sensor.TYPE_LIGHT && event.values.length > 0) {
            text.append("\nОсвещённость: ").append(fmt(event.values[0])).append(" лк");
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY && event.values.length > 0) {
            text.append("\nПриближение: ").append(fmt(event.values[0]))
                    .append(" (макс. ").append(fmt(event.sensor.getMaximumRange())).append(")");
        }
        text.append("\nОбновление: ").append(event.timestamp / 1_000_000L).append(" мс от старта системы");
        liveValues.setText(text.toString());
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private String cameraReport() {
        StringBuilder out = new StringBuilder();
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return "CameraManager недоступен.";
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) return "Камеры не обнаружены.";
            for (String id : ids) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                out.append("Камера ").append(id).append(" · ")
                        .append(facingName(facing))
                        .append("\nВспышка: ").append(Boolean.TRUE.equals(flash) ? "есть" : "нет");
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean depth = false;
                if (caps != null) {
                    for (int cap : caps) {
                        if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) depth = true;
                    }
                }
                out.append(" · DEPTH_OUTPUT: ").append(depth ? "да" : "нет");
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                    if (sizes != null) {
                        out.append("\nРазмеры preview: ");
                        int shown = 0;
                        for (Size size : sizes) {
                            if (shown++ >= 8) { out.append("…"); break; }
                            if (shown > 1) out.append(", ");
                            out.append(size.getWidth()).append("×").append(size.getHeight());
                        }
                    }
                }
                out.append("\n\n");
            }
        } catch (Exception e) {
            out.append("Не удалось прочитать характеристики: ").append(e.getClass().getSimpleName());
        }
        return out.toString().trim();
    }

    private String audioReport() {
        StringBuilder out = new StringBuilder();
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return "AudioManager недоступен.";
        appendAudioDevices(out, "Входы", manager.getDevices(AudioManager.GET_DEVICES_INPUTS));
        appendAudioDevices(out, "Выходы", manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
        return out.toString().trim();
    }

    private void appendAudioDevices(StringBuilder out, String title, AudioDeviceInfo[] devices) {
        out.append(title).append(": ");
        if (devices == null || devices.length == 0) {
            out.append("не обнаружены\n");
            return;
        }
        out.append(devices.length).append("\n");
        for (AudioDeviceInfo device : devices) {
            out.append("• ").append(deviceType(device.getType()))
                    .append(" · ").append(device.getProductName())
                    .append("\n");
            int[] rates = device.getSampleRates();
            if (rates != null && rates.length > 0) {
                out.append("  Частоты: ");
                for (int i = 0; i < rates.length; i++) {
                    if (i > 0) out.append(", ");
                    out.append(rates[i]);
                }
                out.append(" Гц\n");
            } else {
                out.append("  Частоты: Android не сообщил\n");
            }
            int[] channels = device.getChannelCounts();
            if (channels != null && channels.length > 0) {
                out.append("  Каналы: ");
                for (int i = 0; i < channels.length; i++) {
                    if (i > 0) out.append(", ");
                    out.append(channels[i]);
                }
                out.append("\n");
            }
        }
    }

    private String audioFormatReport() {
        StringBuilder out = new StringBuilder();
        int[] rates = {16000, 22050, 44100, 48000};
        for (int rate : rates) {
            int mono = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int stereo = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            out.append(rate).append(" Гц: mono ")
                    .append(bufferResult(mono)).append(" · stereo ")
                    .append(bufferResult(stereo)).append("\n");
        }
        out.append("Положительный буфер означает, что Android принимает такую конфигурацию для запроса; это ещё не доказывает качество записи или доступность обоих микрофонов по отдельности.");
        return out.toString();
    }

    private String bufferResult(int value) {
        if (value == AudioRecord.ERROR_BAD_VALUE) return "не поддерживается";
        if (value == AudioRecord.ERROR) return "ошибка";
        if (value == AudioRecord.ERROR_INVALID_OPERATION) return "неверная операция";
        return value + " байт";
    }

    private String facingName(Integer facing) {
        if (facing == null) return "направление неизвестно";
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "основная";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "фронтальная";
        if (Build.VERSION.SDK_INT >= 23 && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "внешняя";
        return "другая";
    }

    private String deviceType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "встроенный микрофон";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "встроенный динамик";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "разговорный динамик";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "проводная гарнитура";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "проводные наушники";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth-гарнитура";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth-аудио";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB-аудио";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB-гарнитура";
            default: return "аудиоустройство типа " + type;
        }
    }

    private TextView addText(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(7));
        parent.addView(view, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void addPanel(LinearLayout parent, String value) {
        TextView panel = addText(parent, value, 11, TEXT, false);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setBackgroundColor(0xFF152B25);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String fmt(float value) {
        return String.format(Locale.US, "%.3f", value);
    }
}
