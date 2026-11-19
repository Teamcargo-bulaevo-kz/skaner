package kz.bulaevo.skaner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Polar occupancy sketch. A return is drawn only after it repeats in at least
 * three observations from the same bearing sector; raw single-frame peaks
 * are deliberately not painted as objects.
 */
final class RadarView extends View {
    private static final int BG = 0xFF081311;
    private static final int GRID = 0xFF1A4439;
    private static final int ACCENT = 0xFF69E5B5;
    private static final float ANGLE_BIN_DEGREES = 6f;
    private static final int SECTOR_COUNT = 60;
    private static final int HISTORY_LIMIT = 7;
    private static final long HISTORY_AGE_MS = 1400L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Point> points = new ArrayList<>();
    private final SectorHistory[] histories = new SectorHistory[SECTOR_COUNT];
    private float heading;
    private final float maxRange = 6.5f;
    private String hint = "ПОВОРАЧИВАЙТЕ ТЕЛЕФОН МЕДЛЕННО";
    private long lastFrame;

    private static final class Observation {
        final float[] distances;
        final long time;
        Observation(float[] distances, long time) {
            this.distances = distances;
            this.time = time;
        }
    }

    private static final class SectorHistory {
        final ArrayDeque<Observation> observations = new ArrayDeque<>();
    }

    private static final class Point {
        float bearing;
        float range;
        float strength;
        int confirmations;
        long time;
        Point(float bearing, float range, float strength, long time) {
            this.bearing = bearing;
            this.range = range;
            this.strength = strength;
            this.time = time;
            this.confirmations = 1;
        }
    }

    RadarView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(BG);
        for (int i = 0; i < histories.length; i++) histories[i] = new SectorHistory();
    }

    void setHeading(float value) {
        heading = normalize(value);
        invalidate();
    }

    void setHint(String value) {
        hint = value == null ? "" : value;
        postInvalidate();
    }

    void clearMap() {
        points.clear();
        for (SectorHistory history : histories) history.observations.clear();
        invalidate();
    }

    void addMeasurement(float bearing, SonarDsp.Echo[] echoes) {
        long now = System.currentTimeMillis();
        float normalizedBearing = normalize(bearing);
        int sector = (int) Math.floor((normalizedBearing + ANGLE_BIN_DEGREES * 0.5f)
                / ANGLE_BIN_DEGREES) % SECTOR_COUNT;
        SectorHistory history = histories[sector];

        float[] distances = new float[echoes == null ? 0 : echoes.length];
        int validCount = 0;
        if (echoes != null) {
            for (SonarDsp.Echo echo : echoes) {
                if (echo.distanceMeters >= 0.24f && echo.distanceMeters <= maxRange) {
                    distances[validCount++] = echo.distanceMeters;
                }
            }
        }
        float[] validDistances = new float[validCount];
        System.arraycopy(distances, 0, validDistances, 0, validCount);
        history.observations.addLast(new Observation(validDistances, now));
        while (history.observations.size() > HISTORY_LIMIT) history.observations.removeFirst();
        while (!history.observations.isEmpty()
                && now - history.observations.peekFirst().time > HISTORY_AGE_MS) {
            history.observations.removeFirst();
        }

        for (float currentDistance : validDistances) {
            float[] matches = new float[HISTORY_LIMIT];
            int matchCount = 0;
            for (Observation observation : history.observations) {
                float closest = Float.NaN;
                float error = 0.20f;
                for (float observedDistance : observation.distances) {
                    float difference = Math.abs(observedDistance - currentDistance);
                    if (difference <= error) {
                        error = difference;
                        closest = observedDistance;
                    }
                }
                if (!Float.isNaN(closest)) matches[matchCount++] = closest;
            }
            if (matchCount < 3) continue;
            float stableDistance = median(matches, matchCount);
            updateMapPoint(sector * ANGLE_BIN_DEGREES, stableDistance, 0.5f, now);
        }

        Iterator<Point> iterator = points.iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().time > 30000L) iterator.remove();
        }
        while (points.size() > 120) points.remove(0);
        invalidate();
    }

    private void updateMapPoint(float bearing, float range, float strength, long now) {
        Point closest = null;
        float best = Float.MAX_VALUE;
        for (Point point : points) {
            float angleDifference = angularDifference(point.bearing, bearing);
            float rangeDifference = Math.abs(point.range - range);
            if (angleDifference <= ANGLE_BIN_DEGREES * 1.6f && rangeDifference <= 0.26f) {
                float score = rangeDifference + angleDifference * 0.003f;
                if (score < best) {
                    best = score;
                    closest = point;
                }
            }
        }
        if (closest == null) {
            points.add(new Point(bearing, range, strength, now));
        } else {
            closest.bearing = circularBlend(closest.bearing, bearing, 0.35f);
            closest.range = closest.range * 0.35f + range * 0.65f;
            closest.strength = Math.max(closest.strength, strength);
            closest.confirmations = Math.min(20, closest.confirmations + 1);
            closest.time = now;
        }
    }

    private static float median(float[] values, int count) {
        float[] copy = new float[count];
        System.arraycopy(values, 0, copy, 0, count);
        java.util.Arrays.sort(copy);
        if ((count & 1) == 1) return copy[count / 2];
        return (copy[count / 2 - 1] + copy[count / 2]) * 0.5f;
    }

    private static float normalize(float angle) {
        return (angle % 360f + 360f) % 360f;
    }

    private static float angularDifference(float a, float b) {
        float difference = Math.abs(normalize(a) - normalize(b));
        return Math.min(difference, 360f - difference);
    }

    private static float circularBlend(float a, float b, float amount) {
        double ar = Math.toRadians(a), br = Math.toRadians(b);
        double x = (1.0 - amount) * Math.cos(ar) + amount * Math.cos(br);
        double y = (1.0 - amount) * Math.sin(ar) + amount * Math.sin(br);
        return normalize((float) Math.toDegrees(Math.atan2(y, x)));
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
        for (int ring = 1; ring <= 6; ring++) {
            canvas.drawCircle(cx, cy, radius * ring / 6f, paint);
        }
        for (int angle = 0; angle < 360; angle += 30) {
            float radians = (float) Math.toRadians(angle - 90);
            canvas.drawLine(cx, cy, cx + (float) Math.cos(radians) * radius,
                    cy + (float) Math.sin(radians) * radius, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF8BBFB0);
        paint.setTextSize(10f * getResources().getDisplayMetrics().density);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        for (int ring = 1; ring <= 6; ring++) {
            float r = radius * ring / 6f;
            canvas.drawText(ring + " м", cx + 5, cy - r + 13, paint);
        }
        canvas.drawText("N", cx - 4, cy - radius - 8, paint);
        canvas.drawText("E", cx + radius + 6, cy + 4, paint);
        canvas.drawText("S", cx - 3, cy + radius + 15, paint);
        canvas.drawText("W", cx - radius - 17, cy + 4, paint);

        long now = System.currentTimeMillis();
        Iterator<Point> iterator = points.iterator();
        while (iterator.hasNext()) {
            Point point = iterator.next();
            long age = now - point.time;
            if (age > 30000L) {
                iterator.remove();
                continue;
            }
            float r = radius * point.range / maxRange;
            float angle = (float) Math.toRadians(point.bearing - 90.0);
            float x = cx + (float) Math.cos(angle) * r;
            float y = cy + (float) Math.sin(angle) * r;
            int alpha = (int) (Math.max(0.18, 1.0 - age / 35000.0) * 210);
            float dot = 3.0f + Math.min(3.0f, point.confirmations * 0.22f);
            paint.setColor((alpha << 24) | 0x006FE2B1);
            canvas.drawCircle(x, y, dot * 1.8f, paint);
            paint.setColor((alpha << 24) | 0x006FFFCB);
            canvas.drawCircle(x, y, dot * 0.72f, paint);
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
