package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * Twinkling four-pointed stars that drift slowly across the background, and now and then a comet from one edge to the
 * other (`stars=true` in config.properties; the home screen at night and the screensaver). Each colour's glow and
 * comet tail are drawn once into small bitmaps; a frame only draws those bitmaps. A star's place and brightness come
 * from the time alone; drawing allocates nothing.
 */
final class Stars {
    private static final int[] COLORS = {0xFF2F8DFF, 0xFF22E07A, 0xFFE8DCB8};   // blue, green, cream
    private static final int COUNT = 340;
    private static final float SPRITE = 48, STAR = 8;   // the sprite's size and the star's radius in it (room for the glow)
    private static final float TAIL = 340, TAIL_WIDTH = 3.2f, HEAD = 80;   // a comet's, at 1080p
    private static final long COMET_EVERY = 10_000, COMET_SPREAD = 10_000;   // one every 10 to 20 s

    private final float[] x = new float[COUNT], y = new float[COUNT], vx = new float[COUNT], vy = new float[COUNT];
    private final float[] radius = new float[COUNT], base = new float[COUNT], amplitude = new float[COUNT];
    private final float[] speed = new float[COUNT], phase = new float[COUNT];
    private final int[] color = new int[COUNT];
    private final Bitmap[] sprites = new Bitmap[COLORS.length], tails = new Bitmap[COLORS.length];
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Random random = new Random();
    private float w, h, k;

    // The comet crossing now (cometAt 0: none), and when the next one comes.
    private long cometAt, nextComet;
    private float cometMs, fromX, fromY, toX, toY, angle;
    private int cometColor;

    /** New stars over a w×h screen (k: its scale against 1920×1080). Not while drawing: it makes the bitmaps. */
    void setSize(int width, int height, float scale) {
        if (width == w && height == h) return;
        w = width;
        h = height;
        k = scale;
        for (int i = 0; i < COUNT; i++) {
            x[i] = random.nextFloat() * w;
            y[i] = random.nextFloat() * h;
            radius[i] = (random.nextFloat() * 2.5f + 1.5f) * k;
            color[i] = random.nextInt(COLORS.length);
            base[i] = random.nextFloat() * 0.2f + 0.25f;
            amplitude[i] = random.nextFloat() * 0.4f + 0.45f;
            speed[i] = random.nextFloat() * 0.0009f + 0.0004f;   // radians a millisecond: one twinkle every 5 to 16 s
            phase[i] = random.nextFloat() * (float) (Math.PI * 2);
            vx[i] = (random.nextFloat() - 0.5f) * 18 * k;           // pixels a second
            vy[i] = (random.nextFloat() - 0.5f) * 18 * k;
        }
        int size = Math.max(8, Math.round(SPRITE * 2 * k));   // drawn up to about twice its size at 1080p
        for (int i = 0; i < COLORS.length; i++) {
            if (sprites[i] != null) sprites[i].recycle();
            if (tails[i] != null) tails[i].recycle();
            sprites[i] = sprite(COLORS[i], size);
            tails[i] = tail(COLORS[i], Math.max(16, Math.round(TAIL * k)), TAIL_WIDTH * k);
        }
        core.setColor(0xFFFFFFFF);
        cometAt = nextComet = 0;
    }

    /** Draws the stars (and the comet, if one is crossing) at time `now` (uptime ms), at `alpha` (0 draws nothing). */
    void draw(Canvas c, long now, float alpha) {
        if (alpha <= 0.004f || w == 0) return;
        double t = now / 1000.0;   // in doubles: a TV stays up for weeks, and floats would make the stars step
        for (int i = 0; i < COUNT; i++) {
            float sx = wrap(x[i] + vx[i] * t, w), sy = wrap(y[i] + vy[i] * t, h);
            float twinkle = (float) Math.sin(now * (double) speed[i] + phase[i]) * 0.5f + 0.5f;
            float size = radius[i] * 9 * (1 + twinkle * 0.35f) / 2;
            paint.setAlpha(Math.round(255 * alpha * Math.min(1, base[i] + amplitude[i] * twinkle)));
            dst.set(sx - size, sy - size, sx + size, sy + size);
            c.drawBitmap(sprites[color[i]], null, dst, paint);
        }
        drawComet(c, now, alpha);
    }

    /** A comet is crossing, or about to: the caller should draw at the full frame rate, so it doesn't stutter. */
    boolean busy(long now) {
        return cometAt != 0 || nextComet != 0 && nextComet - now < 50;
    }

    private void drawComet(Canvas c, long now, float alpha) {
        if (nextComet == 0) nextComet = now + COMET_EVERY + (long) (random.nextFloat() * COMET_SPREAD);
        if (cometAt == 0 && now >= nextComet) launch(now);
        if (cometAt == 0) return;
        float t = (now - cometAt) / cometMs;
        if (t >= 1) {
            cometAt = 0;
            nextComet = now + COMET_EVERY + (long) (random.nextFloat() * COMET_SPREAD);
            return;
        }
        float fade = alpha * Math.min(1, Math.min(t * 6, (1 - t) * 6));
        float cx = fromX + (toX - fromX) * t, cy = fromY + (toY - fromY) * t;
        Bitmap tail = tails[cometColor];
        paint.setAlpha(Math.round(255 * fade));
        c.save();
        c.translate(cx, cy);
        c.rotate(angle);
        c.drawBitmap(tail, -tail.getWidth(), -tail.getHeight() / 2f, paint);
        c.restore();
        float head = HEAD * k / 2;
        dst.set(cx - head, cy - head, cx + head, cy + head);
        c.drawBitmap(sprites[cometColor], null, dst, paint);
        core.setAlpha(Math.round(255 * fade));
        c.drawCircle(cx, cy, 3.4f * k, core);
    }

    /** From just past one side edge to the opposite one, on a gentle diagonal, in 1.6 to 2.6 s. */
    private void launch(long now) {
        boolean rightward = random.nextBoolean();
        float margin = 0.1f * w;
        fromX = rightward ? -margin : w + margin;
        toX = rightward ? w + margin : -margin;
        fromY = random.nextFloat() * 0.55f * h;
        toY = fromY + (0.15f + random.nextFloat() * 0.35f) * h;
        angle = (float) Math.toDegrees(Math.atan2(toY - fromY, toX - fromX));
        cometMs = 1600 + random.nextFloat() * 1000;
        cometColor = random.nextInt(COLORS.length);
        cometAt = now;
    }

    private static float wrap(double v, float max) {
        v %= max;
        return (float) (v < 0 ? v + max : v);
    }

    /** The star and its glow, once per colour. */
    private static Bitmap sprite(int color, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float s = size / SPRITE, center = size / 2f, outer = STAR * s, inner = outer * 0.32f;
        Path star = new Path();
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI / 4 * i - Math.PI / 2;
            float r = i % 2 == 0 ? outer : inner;
            float px = center + (float) Math.cos(angle) * r, py = center + (float) Math.sin(angle) * r;
            if (i == 0) star.moveTo(px, py);
            else star.lineTo(px, py);
        }
        star.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setMaskFilter(new BlurMaskFilter(SPRITE * 0.3f * s * 0.85f, BlurMaskFilter.Blur.NORMAL));   // the glow
        c.drawPath(star, p);
        p.setMaskFilter(null);
        c.drawPath(star, p);
        c.drawCircle(center, center, outer * 0.28f, p);
        return b;
    }

    /** A comet's tail, pointing left from its head at the right end: white there, fading out through the colour. */
    private static Bitmap tail(int color, int length, float width) {
        float glow = width * 3;
        int height = Math.max(4, Math.round(width + glow * 2));
        Bitmap b = Bitmap.createBitmap(length, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int clear = color & 0x00FFFFFF;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(length, 0, 0, 0, new int[] {0x99000000 | clear, 0x33000000 | clear, clear},
                new float[] {0, 0.3f, 1}, Shader.TileMode.CLAMP));
        p.setMaskFilter(new BlurMaskFilter(glow, BlurMaskFilter.Blur.NORMAL));
        float mid = height / 2f;
        c.drawRect(0, mid - width, length, mid + width, p);   // the glow around it
        p.setMaskFilter(null);
        p.setShader(new LinearGradient(length, 0, 0, 0, new int[] {0xF2FFFFFF, 0x8C000000 | clear, clear},
                new float[] {0, 0.18f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(0, mid - width / 2, length, mid + width / 2, width / 2, width / 2, p);
        return b;
    }
}
