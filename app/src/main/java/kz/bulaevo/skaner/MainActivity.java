package kz.bulaevo.skaner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Collections;

public class MainActivity extends Activity implements SensorEventListener, TextureView.SurfaceTextureListener {
    private static final int REQUEST_MIC = 41;
    private static final int REQUEST_CAMERA = 42;
    private static final int BG = Color.rgb(7, 17, 15);
    private static final int PANEL_LIGHT = Color.rgb(21, 43, 37);
    private static final int TEXT = Color.rgb(229, 242, 236);
    private static final int MUTED = Color.rgb(136, 164, 151);
    private static final int GREEN = Color.rgb(105, 229, 181);

    private LinearLayout root;
    private FrameLayout pageHost;
    private TextView statusText;
    private TextView readingText;
    private RadarView radarView;
    private Button scanButton;
    private Button frequencyButton;
    private boolean highFrequency;
    private boolean scanning;
    private float heading;
    private SonarEngine sonarEngine;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private int currentPage;

    private TextureView cameraTexture;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraSession;
    private CaptureRequest.Builder previewRequest;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Size previewSize;
    private int cameraExposureMin;
    private int cameraExposureMax;
    private int cameraExposureProgress;
    private boolean flashAvailable;
    private boolean torchOn;
    private boolean monochrome;
    private int cameraSensorOrientation = 90;
    private boolean cameraOpening;
    private TextView exposureLabel;
    private Button torchButton;
    private SeekBar exposureSeekBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);
        buildShell();
        showPage(0);
    }

    private void buildShell() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(12), dp(18), dp(10));
        header.addView(label("СКАНЕР", 20, TEXT, true));
        TextView subtitle = label("АКУСТИЧЕСКАЯ КАРТА ПРОСТРАНСТВА", 9, MUTED, true);
        subtitle.setLetterSpacing(0.12f);
        header.addView(subtitle);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(63)));

        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(8), dp(7), dp(8), dp(8));
        navigation.setBackgroundColor(0xFF0B1B17);
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(60)));
        refreshNavigation();
    }

    private void showPage(int page) {
        currentPage = page;
        stopCamera();
        if (page != 0) stopSonar();
        pageHost.removeAllViews();
        if (page == 0) pageHost.addView(buildRadarPage());
        else if (page == 1) pageHost.addView(buildNightPage());
        else pageHost.addView(buildInfoPage());
        if (page == 1) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraTexture.post(this::startCameraIfReady);
            } else {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            }
        }
        refreshNavigation();
    }

    private void refreshNavigation() {
        if (root == null || root.getChildCount() < 3) return;
        LinearLayout navigation = (LinearLayout) root.getChildAt(2);
        navigation.removeAllViews();
        String[] names = {"РАДАР", "НОЧЬ", "О ПРОЕКТЕ"};
        for (int i = 0; i < names.length; i++) {
            final int target = i;
            Button button = makeButton(names[i], i == currentPage);
            button.setTextSize(10);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            navigation.addView(button, lp);
            button.setOnClickListener(v -> showPage(target));
        }
    }

    private View buildRadarPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(7), dp(14), dp(8));

        statusText = label("ОЖИДАНИЕ СКАНИРОВАНИЯ", 11, GREEN, true);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(statusText, new LinearLayout.LayoutParams(-1, dp(28)));

        radarView = new RadarView(this);
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        mapLp.topMargin = dp(3);
        mapLp.bottomMargin = dp(4);
        page.addView(radarView, mapLp);
        radarView.setHeading(heading);

        readingText = label("Дистанция: —     Эхо: —", 12, TEXT, true);
        readingText.setGravity(Gravity.CENTER);
        page.addView(readingText, new LinearLayout.LayoutParams(-1, dp(30)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        scanButton = makeButton("НАЧАТЬ СКАНИРОВАНИЕ", true);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, dp(48), 1.5f);
        startLp.setMargins(0, dp(6), dp(5), dp(5));
        controls.addView(scanButton, startLp);
        scanButton.setOnClickListener(v -> toggleSonar());

        frequencyButton = makeButton("6.5–11.5 кГц", false);
        LinearLayout.LayoutParams freqLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        freqLp.setMargins(dp(5), dp(6), 0, dp(5));
        controls.addView(frequencyButton, freqLp);
        frequencyButton.setOnClickListener(v -> toggleFrequency());
        page.addView(controls, new LinearLayout.LayoutParams(-1, dp(60)));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        Button clear = makeButton("ОЧИСТИТЬ КАРТУ", false);
        clear.setTextSize(10);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        clearLp.setMargins(0, 0, dp(5), 0);
        bottom.addView(clear, clearLp);
        clear.setOnClickListener(v -> {
            if (radarView != null) radarView.clearMap();
            if (readingText != null) readingText.setText("Дистанция: —     Эхо: —");
        });
        TextView compass = label("НИЖНИЙ ДИНАМИК → ВПЕРЁД", 9, MUTED, true);
        compass.setGravity(Gravity.CENTER);
        bottom.addView(compass, new LinearLayout.LayoutParams(0, dp(38), 1f));
        page.addView(bottom);
        TextView note = label("Держите телефон экраном вверх, нижним динамиком в сторону сканирования.", 10, MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(4), dp(5), dp(4), 0);
        page.addView(note, new LinearLayout.LayoutParams(-1, dp(32)));
        updateScanUi();
        return page;
    }

    private View buildNightPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(6), dp(12), dp(9));

        page.addView(label("КАМЕРА · УСИЛЕНИЕ СЛАБОГО СВЕТА", 11, GREEN, true),
                new LinearLayout.LayoutParams(-1, dp(28)));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        cameraTexture = new TextureView(this);
        cameraTexture.setSurfaceTextureListener(this);
        previewFrame.addView(cameraTexture, new FrameLayout.LayoutParams(-1, -1));
        TextView overlay = label("КАМЕРА ОЖИДАЕТ ДОСТУПА", 11, TEXT, true);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0x55000000);
        previewFrame.addView(overlay, new FrameLayout.LayoutParams(-1, dp(36), Gravity.TOP));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        previewLp.topMargin = dp(4);
        previewLp.bottomMargin = dp(8);
        page.addView(previewFrame, previewLp);

        exposureLabel = label("ЭКСПОЗИЦИЯ: АВТО", 11, TEXT, true);
        page.addView(exposureLabel, new LinearLayout.LayoutParams(-1, dp(26)));
        exposureSeekBar = new SeekBar(this);
        exposureSeekBar.setMax(24);
        exposureSeekBar.setProgress(12);
        exposureSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                cameraExposureProgress = progress;
                applyCameraSettings();
                updateExposureLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        page.addView(exposureSeekBar, new LinearLayout.LayoutParams(-1, dp(37)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        torchButton = makeButton("ФОНАРЬ: ВЫКЛ", false);
        LinearLayout.LayoutParams torchLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        torchLp.setMargins(0, dp(4), dp(5), 0);
        actions.addView(torchButton, torchLp);
        torchButton.setOnClickListener(v -> {
            if (!flashAvailable) return;
            torchOn = !torchOn;
            updateTorchUi();
            applyCameraSettings();
        });
        Button monoButton = makeButton(monochrome ? "Ч/Б: ВКЛ" : "Ч/Б: ВЫКЛ", monochrome);
        monoButton.setTextSize(10);
        LinearLayout.LayoutParams monoLp = new LinearLayout.LayoutParams(0, dp(44), 0.85f);
        monoLp.setMargins(dp(4), dp(4), dp(4), 0);
        actions.addView(monoButton, monoLp);
        monoButton.setOnClickListener(v -> {
            monochrome = !monochrome;
            monoButton.setText(monochrome ? "Ч/Б: ВКЛ" : "Ч/Б: ВЫКЛ");
            applyCameraSettings();
        });
        Button retry = makeButton("ОБНОВИТЬ", false);
        retry.setTextSize(10);
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(0, dp(44), 0.8f);
        retryLp.setMargins(dp(4), dp(4), 0, 0);
        actions.addView(retry, retryLp);
        retry.setOnClickListener(v -> {
            stopCamera();
            startCameraIfReady();
        });
        page.addView(actions, new LinearLayout.LayoutParams(-1, dp(53)));

        TextView note = label("Экспозиция помогает при слабом свете. В полной темноте обычная камера не видит без подсветки.", 10, MUTED, false);
        note.setPadding(dp(3), dp(6), dp(3), 0);
        page.addView(note, new LinearLayout.LayoutParams(-1, dp(40)));
        updateTorchUi();
        updateExposureLabel();
        if (cameraTexture.isAvailable()) cameraTexture.post(this::startCameraIfReady);
        return page;
    }

    private View buildInfoPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(12));
        addInfoBlock(page, "КАК РАБОТАЕТ РАДАР",
                "Динамик посылает короткий частотный сигнал. Микрофон записывает его и отражения. Сопоставление сигнала с исходным импульсом помогает оценить расстояние.");
        addInfoBlock(page, "КАРТА КОМНАТЫ",
                "Медленно поворачивайте телефон по кругу. Каждое найденное отражение сохраняется на полярной карте по направлению и приблизительной дистанции. Для ориентации держите экран вверх, нижний динамик направляйте наружу.");
        addInfoBlock(page, "НОЧНОЙ РЕЖИМ",
                "Камера использует возможности автоэкспозиции телефона и компенсацию экспозиции. Можно включить фонарь. Это не тепловизор и не прибор ночного видения: без видимого света камера не различит предметы.");
        addInfoBlock(page, "ЧЕСТНЫЕ ОГРАНИЧЕНИЯ",
                "Эхо показывает отражающую поверхность, а не название предмета. Стену можно предполагать по устойчивой линии отражений на нескольких углах; отличить стул от коробки только по одному микрофону нельзя. Точность ограничена акустикой телефона и многолучевыми отражениями.");
        addInfoBlock(page, "ИК И ДАТЧИКИ ГЛУБИНЫ",
                "ИК-порт сверху — передатчик команд пульта, а не приёмник дальномера. Датчик приближения спереди работает как ближний режим «рядом/далеко», не как точный измеритель метров. 2-МП камера глубины предназначена для съёмки/боке; это не лазерный ToF-сканер.");
        addInfoBlock(page, "КАКИЕ ДАННЫЕ МОЖНО ОБЪЕДИНИТЬ",
                "Акустика даёт грубые расстояния до отражателей; гироскоп и компас — направление; камера при освещении помогает распознавать видимые предметы. Для настоящей карты глубины нужен Depth API или отдельный ToF/ультразвуковой модуль. Redmi Note 8 в списке ARCore есть, но пометки о поддержке Depth API у него нет.");
        TextView diagnostics = label(getHardwareDiagnostics(), 10, GREEN, false);
        diagnostics.setLineSpacing(dp(2), 1f);
        diagnostics.setPadding(0, dp(8), 0, dp(4));
        page.addView(diagnostics);
        TextView device = label("ЦЕЛЕВОЕ УСТРОЙСТВО: REDMI NOTE 8 · 4 ГБ ОЗУ", 10, GREEN, true);
        device.setPadding(0, dp(8), 0, 0);
        page.addView(device);
        return page;
    }


    private String getHardwareDiagnostics() {
        Sensor proximity = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        String proximityText = proximity == null ? "датчик приближения: не найден"
                : String.format(java.util.Locale.US,
                        "датчик приближения: найден (API-диапазон %.1f см; это не точный дальномер)",
                        proximity.getMaximumRange());
        boolean consumerIr = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR);
        String irText = consumerIr ? "ИК-порт: передатчик команд пульта"
                : "ИК-передатчик: Android не объявляет эту функцию";
        String depthText = hasDepthOutputCamera()
                ? "камера: найден поток DEPTH16"
                : "камера: отдельный поток глубины DEPTH16 не обнаружен";
        return "ДИАГНОСТИКА УСТРОЙСТВА\n" + proximityText + "\n" + irText + "\n" + depthText;
    }

    private boolean hasDepthOutputCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return false;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                int[] capabilities = characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if (capabilities == null) continue;
                for (int capability : capabilities) {
                    if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void addInfoBlock(LinearLayout page, String title, String body) {
        TextView titleView = label(title, 11, GREEN, true);
        titleView.setPadding(0, dp(11), 0, dp(4));
        page.addView(titleView);
        TextView bodyView = label(body, 12, TEXT, false);
        bodyView.setLineSpacing(dp(2), 1f);
        page.addView(bodyView);
    }

    private void toggleFrequency() {
        highFrequency = !highFrequency;
        if (frequencyButton != null) frequencyButton.setText(highFrequency ? "9.5–14.5 кГц" : "6.5–11.5 кГц");
        if (scanning) {
            stopSonar();
            startSonar();
        }
    }

    private void toggleSonar() {
        if (scanning) stopSonar();
        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        } else {
            startSonar();
        }
    }

    private void startSonar() {
        if (scanning || currentPage != 0) return;
        scanning = true;
        updateScanUi();
        sonarEngine = new SonarEngine(new SonarEngine.Listener() {
            @Override public void onStatus(String status) {
                runOnUiThread(() -> {
                    if (statusText != null) statusText.setText(status.toUpperCase());
                    if (radarView != null) radarView.setHint("ПОВЕРНИТЕ ТЕЛЕФОН · ЭХО ОБНОВЛЯЕТСЯ");
                });
            }
            @Override public void onMeasurement(SonarDsp.Echo[] echoes, float signalStrength) {
                runOnUiThread(() -> {
                    if (!scanning || radarView == null) return;
                    radarView.setHeading(heading);
                    radarView.addMeasurement(heading, echoes);
                    if (readingText != null) {
                        if (echoes.length == 0) {
                            readingText.setText(String.format(java.util.Locale.US,
                                    "Прямой сигнал: %d%%     Эхо: —", Math.round(signalStrength * 100)));
                        } else {
                            StringBuilder distances = new StringBuilder();
                            for (int i = 0; i < Math.min(3, echoes.length); i++) {
                                if (i > 0) distances.append("  ·  ");
                                distances.append(String.format(java.util.Locale.US, "%.1f м",
                                        echoes[i].distanceMeters));
                            }
                            readingText.setText("ЭХО: " + distances);
                        }
                    }
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    scanning = false;
                    updateScanUi();
                    if (statusText != null) statusText.setText("ОШИБКА АУДИО");
                    if (readingText != null) readingText.setText(message);
                    if (sonarEngine != null) sonarEngine.stop();
                });
            }
        });
        sonarEngine.start(highFrequency);
    }

    private void stopSonar() {
        scanning = false;
        if (sonarEngine != null) {
            SonarEngine old = sonarEngine;
            sonarEngine = null;
            old.stop();
        }
        updateScanUi();
        if (statusText != null && currentPage == 0) statusText.setText("СКАНИРОВАНИЕ ОСТАНОВЛЕНО");
        if (radarView != null) radarView.setHint("НАЖМИТЕ «НАЧАТЬ» И МЕДЛЕННО ПОВОРАЧИВАЙТЕ ТЕЛЕФОН");
    }

    private void updateScanUi() {
        if (scanButton != null) {
            scanButton.setText(scanning ? "ОСТАНОВИТЬ" : "НАЧАТЬ СКАНИРОВАНИЕ");
            scanButton.setTextColor(scanning ? 0xFFFFB07A : GREEN);
        }
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private Button makeButton(String text, boolean selected) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(selected ? GREEN : TEXT);
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? 0xFF17362C : PANEL_LIGHT);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), selected ? 0xFF316C56 : 0xFF203D33);
        button.setBackground(background);
        button.setPadding(dp(7), 0, dp(7), 0);
        return button;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onResume() {
        super.onResume();
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (currentPage == 1 && cameraTexture != null) cameraTexture.post(this::startCameraIfReady);
    }

    @Override protected void onPause() {
        stopSonar();
        stopCamera();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopSonar();
        stopCamera();
        super.onDestroy();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        float[] matrix = new float[9];
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        float worldX = -matrix[1];
        float worldY = -matrix[4];
        float horizontal = (float) Math.sqrt(worldX * worldX + worldY * worldY);
        if (horizontal > 0.22f) {
            heading = (float) Math.toDegrees(Math.atan2(worldX, worldY));
        } else {
            float[] orientation = new float[3];
            SensorManager.getOrientation(matrix, orientation);
            heading = (float) Math.toDegrees(orientation[0]);
        }
        heading = (heading + 360f) % 360f;
        if (radarView != null) radarView.setHeading(heading);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_MIC) {
            if (granted) startSonar();
            else if (statusText != null) statusText.setText("НУЖЕН ДОСТУП К МИКРОФОНУ");
        } else if (requestCode == REQUEST_CAMERA && granted) {
            startCameraIfReady();
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("Skaner-Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    @SuppressLint("MissingPermission")
    private void startCameraIfReady() {
        if (currentPage != 1 || cameraOpening || cameraDevice != null
                || cameraTexture == null || !cameraTexture.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        startCameraThread();
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String selected = null;
            CameraCharacteristics selectedCharacteristics = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    selected = id;
                    selectedCharacteristics = characteristics;
                    break;
                }
            }
            if (selected == null) throw new IllegalStateException("Основная камера не найдена");

            Boolean flash = selectedCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flashAvailable = flash != null && flash;
            android.util.Range<Integer> range =
                    selectedCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            cameraSensorOrientation = selectedCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) == null
                    ? 90 : selectedCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            cameraExposureMin = range == null ? 0 : range.getLower();
            cameraExposureMax = range == null ? 0 : range.getUpper();
            cameraExposureProgress = Math.max(0, Math.min(
                    cameraExposureMax - cameraExposureMin, -cameraExposureMin));
            if (exposureSeekBar != null) {
                exposureSeekBar.setMax(Math.max(1, cameraExposureMax - cameraExposureMin));
                exposureSeekBar.setProgress(cameraExposureProgress);
            }
            updateTorchUi();
            updateExposureLabel();

            StreamConfigurationMap map =
                    selectedCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(android.graphics.SurfaceTexture.class);
            previewSize = choosePreviewSize(sizes);
            if (cameraTexture.getWidth() > 0 && cameraTexture.getHeight() > 0) {
                configureTransform(cameraTexture.getWidth(), cameraTexture.getHeight());
            }
            cameraOpening = true;
            manager.openCamera(selected, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraOpening = false;
                    cameraDevice = camera;
                    createCameraPreview();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    cameraOpening = false;
                    camera.close();
                    cameraDevice = null;
                }
                @Override public void onError(CameraDevice camera, int error) {
                    cameraOpening = false;
                    camera.close();
                    cameraDevice = null;
                }
            }, cameraHandler);
        } catch (Exception error) {
            cameraOpening = false;
            if (exposureLabel != null) exposureLabel.setText("КАМЕРА: " + error.getMessage());
        }
    }

    private Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size size : sizes) {
            if (size.getWidth() > 1920 || size.getHeight() > 1440) continue;
            long score = Math.abs((long) size.getWidth() - 1280)
                    + Math.abs((long) size.getHeight() - 720);
            if (score < bestScore) {
                bestScore = score;
                best = size;
            }
        }
        return best;
    }

    private void createCameraPreview() {
        if (cameraDevice == null || cameraTexture == null || !cameraTexture.isAvailable()) return;
        try {
            android.graphics.SurfaceTexture texture = cameraTexture.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            previewRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(surface);
            applyCameraSettings();
            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            cameraSession = session;
                            applyCameraSettings();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            if (exposureLabel != null) exposureLabel.setText("НЕ УДАЛОСЬ ЗАПУСТИТЬ PREVIEW");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException error) {
            if (exposureLabel != null) exposureLabel.setText("ОШИБКА КАМЕРЫ: " + error.getMessage());
        }
    }

    private void applyCameraSettings() {
        if (previewRequest == null) return;
        try {
            previewRequest.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            previewRequest.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            int compensation = Math.max(cameraExposureMin,
                    Math.min(cameraExposureMax, cameraExposureMin + cameraExposureProgress));
            previewRequest.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation);
            previewRequest.set(CaptureRequest.CONTROL_EFFECT_MODE, monochrome
                    ? CaptureRequest.CONTROL_EFFECT_MODE_MONO : CaptureRequest.CONTROL_EFFECT_MODE_OFF);
            previewRequest.set(CaptureRequest.FLASH_MODE, torchOn && flashAvailable
                    ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            if (cameraSession != null) {
                cameraSession.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
            }
        } catch (CameraAccessException | IllegalArgumentException ignored) { }
    }

    private void updateExposureLabel() {
        if (exposureLabel == null) return;
        int steps = Math.max(cameraExposureMin,
                Math.min(cameraExposureMax, cameraExposureMin + cameraExposureProgress));
        exposureLabel.setText(steps == 0 ? "ЭКСПОЗИЦИЯ: АВТО"
                : String.format(java.util.Locale.US, "ЭКСПОЗИЦИЯ: %+.1f EV", steps / 3.0));
    }

    private void updateTorchUi() {
        if (torchButton != null) {
            torchButton.setText(torchOn ? "ФОНАРЬ: ВКЛ" : "ФОНАРЬ: ВЫКЛ");
            torchButton.setTextColor(torchOn ? GREEN : TEXT);
            torchButton.setEnabled(flashAvailable);
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (cameraTexture == null || previewSize == null || viewWidth == 0 || viewHeight == 0) return;
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        int displayDegrees = displayRotation == Surface.ROTATION_90 ? 90
                : displayRotation == Surface.ROTATION_180 ? 180
                : displayRotation == Surface.ROTATION_270 ? 270 : 0;
        int relativeRotation = (cameraSensorOrientation - displayDegrees + 360) % 360;
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        boolean swap = relativeRotation == 90 || relativeRotation == 270;
        RectF bufferRect = swap
                ? new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth())
                : new RectF(0, 0, previewSize.getWidth(), previewSize.getHeight());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float rotatedWidth = swap ? previewSize.getHeight() : previewSize.getWidth();
        float rotatedHeight = swap ? previewSize.getWidth() : previewSize.getHeight();
        float scale = Math.max((float) viewHeight / rotatedHeight,
                (float) viewWidth / rotatedWidth);
        matrix.postScale(scale, scale, centerX, centerY);
        matrix.postRotate(relativeRotation, centerX, centerY);
        cameraTexture.setTransform(matrix);
    }

    private void stopCamera() {
        cameraOpening = false;
        try { if (cameraSession != null) cameraSession.close(); } catch (Exception ignored) { }
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) { }
        cameraSession = null;
        cameraDevice = null;
        previewRequest = null;
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(350); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
        torchOn = false;
        updateTorchUi();
    }

    @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
        startCameraIfReady();
    }

    @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
        configureTransform(width, height);
    }

    @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
        stopCamera();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) { }
}
