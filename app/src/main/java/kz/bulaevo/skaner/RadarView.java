package kz.bulaevo.skaner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Lightweight polar echo map; points accumulate as the user slowly turns the phone. */
final class RadarView extends View {
    private static final int BG = 0xFF081311;
    private static final int GRID = 0xFF1A4439;
    private static final int ACCENT = 0xFF69E5B5;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Point> points = new ArrayList<>();
    private float heading;
    private final float maxRange = 6.5f;
    private String hint = "ПОВЕРНИТЕ ТЕЛЕФОН МЕДЛЕННО";
    private long lastFrame;

    private static final class Point {
        final float bearing, range, strength;
        final long time;
        Point(float bearing, float range, float strength, long time) {
            this.bearing = bearing;
            this.range = range;
            this.strength = strength;
            this.time = time;
        }
    }

    RadarView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(BG);
    }

    void setHeading(float value) {
        heading = value;
        invalidate();
    }

    void setHint(String value) {
        hint = value == null ? "" : value;
        postInvalidate();
    }

    void clearMap() {
        points.clear();
        invalidate();
    }

    void addMeasurement(float bearing, SonarDsp.Echo[] echoes) {
        long now = System.currentTimeMillis();
        if (echoes != null) {
            for (SonarDsp.Echo echo : echoes) {
                if (echo.distanceMeters > 0.2f && echo.distanceMeters <= maxRange) {
                    points.add(new Point(bearing, echo.distanceMeters, echo.strength, now));
                }
            }
        }
        while (points.size() > 700) points.remove(0);
        invalidate();
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
        for (int ring = 1; ring <= 6; ring++) canvas.drawCircle(cx, cy, radius * ring / 6f, paint);
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
            if (age > 60000) {
                iterator.remove();
                continue;
            }
            float r = radius * point.range / maxRange;
            float angle = (float) Math.toRadians(point.bearing - 90.0);
            float x = cx + (float) Math.cos(angle) * r;
            float y = cy + (float) Math.sin(angle) * r;
            int alpha = (int) (Math.max(0.15, 1.0 - age / 70000.0) * 230);
            float dot = 3.0f + Math.min(4.5f, point.strength * 8.0f);
            paint.setColor((alpha << 24) | 0x006FE2B1);
            canvas.drawCircle(x, y, dot * 2.1f, paint);
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
