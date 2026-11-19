package kz.bulaevo.skaner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Conservative polar sketch: one nearest, repeatable return per bearing sector.
 * A burst of frames in one direction is time-throttled so it cannot create
 * dozens of dots from the same multipath pattern.
 */
final class RadarView extends View {
    private static final int BG = 0xFF081311;
    private static final int GRID = 0xFF1A4439;
    private static final int ACCENT = 0xFF69E5B5;
    private static final float BIN_DEGREES = 8f;
    private static final int SECTOR_COUNT = 45;
    private static final int HISTORY_LIMIT = 12;
    private static final long MIN_SAMPLE_SPACING_MS = 100L;
    private static final long HISTORY_AGE_MS = 6000L;
    private static final long MAP_POINT_AGE_MS = 45000L;
    private float maxRange = 2.0f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Sector[] sectors = new Sector[SECTOR_COUNT];
    private float heading;
    private String hint = "МЕДЛЕННО ПОВОРАЧИВАЙТЕ ТЕЛЕФОН";
    private long lastFrame;

    private static final class Sample {
        final float distance;
        final long time;
        Sample(float distance, long time) { this.distance = distance; this.time = time; }
    }

    private static final class Sector {
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        float distance = -1f;
        int confirmations;
        long updatedAt;
        long lastSampleAt;
    }

    RadarView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(BG);
        for (int i = 0; i < sectors.length; i++) sectors[i] = new Sector();
    }

    void setHeading(float value) {
        heading = normalize(value);
        invalidate();
    }

    void setMaxRange(float value) {
        maxRange = Math.max(2.0f, Math.min(10.0f, value));
        invalidate();
    }

    void setHint(String value) {
        hint = value == null ? "" : value;
        postInvalidate();
    }

    void clearMap() {
        for (Sector sector : sectors) {
            sector.samples.clear();
            sector.distance = -1f;
            sector.confirmations = 0;
            sector.updatedAt = 0L;
            sector.lastSampleAt = 0L;
        }
        invalidate();
    }

    void addMeasurement(float bearing, SonarDsp.Echo[] echoes) {
        long now = System.currentTimeMillis();
        int index = (int) Math.floor((normalize(bearing) + BIN_DEGREES * 0.5f)
                / BIN_DEGREES) % SECTOR_COUNT;
        Sector sector = sectors[index];

        // Keep only the nearest credible return. Later echoes are commonly
        // multipath reflections from the same wall and inflate the point cloud.
        float nearest = -1f;
        if (echoes != null) {
            for (SonarDsp.Echo echo : echoes) {
                if (echo != null && echo.strength >= 0.065f
                        && echo.distanceMeters >= 0.24f && echo.distanceMeters <= maxRange
                        && (nearest < 0f || echo.distanceMeters < nearest)) {
                    nearest = echo.distanceMeters;
                }
            }
        }

        if (now - sector.lastSampleAt >= MIN_SAMPLE_SPACING_MS) {
            sector.lastSampleAt = now;
            if (nearest > 0f) sector.samples.addLast(new Sample(nearest, now));
            else if (!sector.samples.isEmpty()) {
                // Missing returns are not evidence of a surface; let old data age out.
            }
        }

        while (!sector.samples.isEmpty()
                && now - sector.samples.peekFirst().time > HISTORY_AGE_MS) {
            sector.samples.removeFirst();
        }

        while (sector.samples.size() > 24) sector.samples.removeFirst();

        if (sector.samples.size() >= 3) {
            float[] values = new float[sector.samples.size()];
            int n = 0;
            for (Sample sample : sector.samples) values[n++] = sample.distance;
            Arrays.sort(values);
            float median = median(values);
            float[] deviations = new float[n];
            for (int i = 0; i < n; i++) deviations[i] = Math.abs(values[i] - median);
            Arrays.sort(deviations);
            float mad = median(deviations);
            // Reject unstable multipath peaks instead of painting them as objects.
            if (mad <= 0.32f && values[n - 1] - values[0] <= 1.00f) {
                sector.distance = median;
                sector.confirmations = n;
                sector.updatedAt = now;
            }
        }

        for (Sector item : sectors) {
            if (item.distance > 0f && now - item.updatedAt > MAP_POINT_AGE_MS) {
                item.distance = -1f;
                item.confirmations = 0;
            }
        }
        invalidate();
    }

    private static float median(float[] values) {
        int n = values.length;
        return (n & 1) == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) * 0.5f;
    }

    private static float normalize(float angle) {
        return (angle % 360f + 360f) % 360f;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.drawColor(BG);
        float cx = width * 0.5f, cy = height * 0.53f;
        float radius = Math.min(width * 0.445f, height * 0.43f);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(GRID);
        for (int ring = 1; ring <= 5; ring++) canvas.drawCircle(cx, cy, radius * ring / 5f, paint);
        for (int angle = 0; angle < 360; angle += 30) {
            float radians = (float) Math.toRadians(angle - 90);
            canvas.drawLine(cx, cy, cx + (float) Math.cos(radians) * radius,
                    cy + (float) Math.sin(radians) * radius, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF8BBFB0);
        paint.setTextSize(10f * getResources().getDisplayMetrics().density);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        for (int ring = 1; ring <= 5; ring++) {
            float r = radius * ring / 5f;
            float metres = maxRange * ring / 5.0f;
            String label = maxRange <= 2.1f
                    ? String.format(java.util.Locale.US, "%.1f м", metres)
                    : String.format(java.util.Locale.US, "%.0f м", metres);
            canvas.drawText(label, cx + 5, cy - r + 13, paint);
        }
        canvas.drawText("N", cx - 4, cy - radius - 8, paint);
        canvas.drawText("E", cx + radius + 6, cy + 4, paint);
        canvas.drawText("S", cx - 3, cy + radius + 15, paint);
        canvas.drawText("W", cx - radius - 17, cy + 4, paint);

        long now = System.currentTimeMillis();
        Path contour = new Path();
        boolean hasPrevious = false;
        float previousX = 0f, previousY = 0f;
        int previousIndex = -2;
        for (int i = 0; i < sectors.length; i++) {
            Sector sector = sectors[i];
            if (sector.distance <= 0f || now - sector.updatedAt > MAP_POINT_AGE_MS) {
                hasPrevious = false;
                previousIndex = -2;
                continue;
            }
            float r = radius * sector.distance / maxRange;
            float angle = (float) Math.toRadians(i * BIN_DEGREES - 90.0);
            float x = cx + (float) Math.cos(angle) * r;
            float y = cy + (float) Math.sin(angle) * r;
            int alpha = (int) (Math.max(0.20, 1.0 - (now - sector.updatedAt) / (double) MAP_POINT_AGE_MS) * 220);
            float dot = 3.0f + Math.min(2.5f, sector.confirmations * 0.18f);
            paint.setColor((alpha << 24) | 0x006FE2B1);
            canvas.drawCircle(x, y, dot * 1.7f, paint);
            paint.setColor((alpha << 24) | 0x006FFFCB);
            canvas.drawCircle(x, y, dot * 0.65f, paint);

            if (hasPrevious && i == previousIndex + 1) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f);
                paint.setColor((alpha << 24) | 0x0069E5B5);
                canvas.drawLine(previousX, previousY, x, y, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            previousX = x;
            previousY = y;
            previousIndex = i;
            hasPrevious = true;
        }

        float sweep = (float) Math.toRadians(heading - 90.0);
        paint.setColor(0xB269E5B5);
        paint.setStrokeWidth(2.0f);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(cx, cy, cx + (float) Math.cos(sweep) * radius,
                cy + (float) Math.sin(sweep) * radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ACCENT);
        canvas.drawCircle(cx, cy, 4.5f, paint);
        paint.setColor(0xFF8CA99F);
        paint.setTextSize(10f * getResources().getDisplayMetrics().density);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        canvas.drawText(hint, 14, height - 12, paint);
        if (now - lastFrame > 500) {
            lastFrame = now;
            postInvalidateDelayed(500);
        }
    }
}
