package com.ktc.launcher;

import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.PathInterpolator;

import java.util.Arrays;

/**
 * Lightweight, zero-blur GPU radial ambient backlight for the Obsidian Spatial launcher.
 *
 * Draws a deep OLED-grade obsidian base, dual-tone cinema radial washes that cross-fade smoothly
 * toward the focused tile's accent color and screen position, and a cached 64x64 procedural
 * micro-noise dither tile that eliminates 8-bit gradient banding at ~0 runtime cost.
 */
public final class AmbientBackdropDrawable extends Drawable {

    public static final int DEFAULT_ACCENT = 0xFF6B8CC7;

    private static final PathInterpolator EASE_OUT = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private static Paint sharedNoisePaint;
    private static float[] srgbToLinLut;

    private static float[] getSrgbToLinLut() {
        if (srgbToLinLut != null) return srgbToLinLut;
        float[] lut = new float[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = (float) Math.pow(i / 255.0, 2.2);
        }
        srgbToLinLut = lut;
        return lut;
    }

    private static int linToSrgbByte(float lin) {
        if (lin <= 0f) return 0;
        if (lin >= 1f) return 255;
        return Math.min(255, Math.max(0, Math.round((float) Math.pow(lin, 1.0 / 2.2) * 255f)));
    }

    private final Paint basePaint = new Paint(Paint.DITHER_FLAG);
    private final Paint topWashPaint = new Paint(Paint.DITHER_FLAG);
    private final Paint spotWashPaint = new Paint(Paint.DITHER_FLAG);
    private final Paint auraPaint = new Paint(Paint.DITHER_FLAG);
    private final Matrix auraMatrix = new Matrix();
    private final float[] hsvBuf = new float[3];

    private int currentColor = DEFAULT_ACCENT;
    private float focusNormX = 0.20f;
    private float focusNormY = 0.30f;
    private float focusNormW = 0.20f;
    private float focusNormH = 0.14f;

    private boolean baseDirty = true;
    private boolean washDirty = true;
    private int lastW = 0;
    private int lastH = 0;

    private ValueAnimator animator;

    private static Paint getNoisePaint() {
        if (sharedNoisePaint != null) return sharedNoisePaint;
        final int size = 64;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int h = x * 374761393 + y * 668265263;
                h = (h ^ (h >> 13)) * 1274126177;
                h = h ^ (h >> 16);
                int v = h & 0xFF;
                // Balanced micro-grain (~1.8% peak alpha) erases 8-bit FRC Mach banding
                if (v < 128) {
                    int a = ((127 - v) * 5) >> 7;
                    pixels[y * size + x] = Color.argb(a, 0, 0, 0);
                } else {
                    int a = ((v - 128) * 5) >> 7;
                    pixels[y * size + x] = Color.argb(a, 255, 255, 255);
                }
            }
        }
        Bitmap noiseBmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new BitmapShader(noiseBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        sharedNoisePaint = p;
        return p;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        baseDirty = true;
        washDirty = true;
    }

    /**
     * Smoothly transitions the ambient backlight color and aspect-matched backlit aura to hug
     * the focused element's exact screen bounds.
     */
    public void transitionTo(
            int targetColor,
            float targetNormX,
            float targetNormY,
            float targetNormW,
            float targetNormH
    ) {
        final float clampedX = Math.max(0.05f, Math.min(0.95f, targetNormX));
        final float clampedY = Math.max(0.05f, Math.min(0.92f, targetNormY));
        final float clampedW = Math.max(0.04f, Math.min(0.50f, targetNormW));
        final float clampedH = Math.max(0.05f, Math.min(0.45f, targetNormH));

        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        final int startColor = currentColor;
        final float startX = focusNormX;
        final float startY = focusNormY;
        final float startW = focusNormW;
        final float startH = focusNormH;

        if (startColor == targetColor
                && Math.abs(startX - clampedX) < 0.005f
                && Math.abs(startY - clampedY) < 0.005f
                && Math.abs(startW - clampedW) < 0.005f
                && Math.abs(startH - clampedH) < 0.005f) {
            return;
        }

        final int sr = Color.red(startColor);
        final int sg = Color.green(startColor);
        final int sb = Color.blue(startColor);
        final int tr = Color.red(targetColor);
        final int tg = Color.green(targetColor);
        final int tb = Color.blue(targetColor);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(300L);
        animator.setInterpolator(EASE_OUT);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                float f = (Float) anim.getAnimatedValue();
                int r = (int) (sr + (tr - sr) * f);
                int g = (int) (sg + (tg - sg) * f);
                int b = (int) (sb + (tb - sb) * f);
                currentColor = Color.rgb(r, g, b);
                focusNormX = startX + (clampedX - startX) * f;
                focusNormY = startY + (clampedY - startY) * f;
                focusNormW = startW + (clampedW - startW) * f;
                focusNormH = startH + (clampedH - startH) * f;
                washDirty = true;
                invalidateSelf();
            }
        });
        animator.start();
    }

    public void setImmediate(int color, float normX, float normY, float normW, float normH) {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        currentColor = color;
        focusNormX = Math.max(0.05f, Math.min(0.95f, normX));
        focusNormY = Math.max(0.05f, Math.min(0.92f, normY));
        focusNormW = Math.max(0.04f, Math.min(0.50f, normW));
        focusNormH = Math.max(0.05f, Math.min(0.45f, normH));
        washDirty = true;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        if (baseDirty || w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            basePaint.setShader(new LinearGradient(
                    0f, 0f, 0f, h,
                    new int[] { 0xFF0B0E15, 0xFF07080C, 0xFF040507 },
                    new float[] { 0f, 0.56f, 1f },
                    Shader.TileMode.CLAMP
            ));
            baseDirty = false;
            washDirty = true;
        }

        if (washDirty) {
            int r = Color.red(currentColor);
            int g = Color.green(currentColor);
            int bl = Color.blue(currentColor);

            // Harmonious hue-shifted (+16 deg) overhead cinema atmosphere wash
            Color.colorToHSV(currentColor, hsvBuf);
            hsvBuf[0] = (hsvBuf[0] + 16f) % 360f;
            hsvBuf[1] = Math.min(0.78f, hsvBuf[1] * 0.94f);
            int topAccent = Color.HSVToColor(hsvBuf);
            int tr = Color.red(topAccent);
            int tg = Color.green(topAccent);
            int tb = Color.blue(topAccent);

            float topRadius = Math.max(w, h) * 0.80f;
            topWashPaint.setShader(new RadialGradient(
                    w * 0.50f, -h * 0.14f, topRadius,
                    new int[] {
                            Color.argb(0x28, tr, tg, tb),
                            Color.argb(0x0E, tr, tg, tb),
                            Color.argb(0x00, tr, tg, tb)
                    },
                    new float[] { 0f, 0.50f, 1f },
                    Shader.TileMode.CLAMP
            ));

            float cx = w * focusNormX;
            float cy = h * focusNormY;

            // Broad soft room reflection around focused region
            float spotRadius = w * 0.42f;
            spotWashPaint.setShader(new RadialGradient(
                    cx, cy, spotRadius,
                    new int[] {
                            Color.argb(0x1E, r, g, bl),
                            Color.argb(0x09, r, g, bl),
                            Color.argb(0x00, r, g, bl)
                    },
                    new float[] { 0f, 0.52f, 1f },
                    Shader.TileMode.CLAMP
            ));

            // Tight aspect-matched backlight aura hugging all 4 edges of the focused card/tile
            // so background glow and backlit bevel rim brightness share one physical light source
            float rx = Math.max(w * 0.045f, w * focusNormW * 0.86f);
            float ry = Math.max(h * 0.065f, h * focusNormH * 1.06f);
            RadialGradient unitAura = new RadialGradient(
                    0f, 0f, 1f,
                    new int[] {
                            Color.argb(0x5E, r, g, bl),
                            Color.argb(0x46, r, g, bl),
                            Color.argb(0x1C, r, g, bl),
                            Color.argb(0x06, r, g, bl),
                            Color.argb(0x00, r, g, bl)
                    },
                    new float[] { 0f, 0.54f, 0.74f, 0.89f, 1f },
                    Shader.TileMode.CLAMP
            );
            auraMatrix.reset();
            auraMatrix.setScale(rx, ry);
            auraMatrix.postTranslate(cx, cy);
            unitAura.setLocalMatrix(auraMatrix);
            auraPaint.setShader(unitAura);

            washDirty = false;
        }

        canvas.drawRect(b, basePaint);
        canvas.drawRect(b, topWashPaint);
        canvas.drawRect(b, spotWashPaint);
        canvas.drawRect(b, auraPaint);
        canvas.drawRect(b, getNoisePaint());
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter colorFilter) {}

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    /**
     * Samples a drawable into a tiny 12x8 bitmap once to extract a refined, cinema-grade
     * dominant accent color. Discards the temporary bitmap immediately (~0 retained memory).
     */
    public static int extractAccentColor(Drawable drawable, int fallbackColor) {
        if (drawable == null) return fallbackColor;
        final int sampleW = 12;
        final int sampleH = 8;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Rect oldBounds = new Rect(drawable.getBounds());
            drawable.setBounds(0, 0, sampleW, sampleH);
            drawable.draw(c);
            drawable.setBounds(oldBounds);

            float[] hsv = new float[3];
            float bestScore = -1f;
            int bestColor = fallbackColor;

            for (int y = 0; y < sampleH; y++) {
                for (int x = 0; x < sampleW; x++) {
                    int px = bmp.getPixel(x, y);
                    if (Color.alpha(px) < 160) continue;
                    Color.colorToHSV(px, hsv);
                    float s = hsv[1];
                    float v = hsv[2];
                    if (v < 0.16f || (v > 0.93f && s < 0.14f) || s < 0.12f) continue;

                    float brightnessWeight = 1f - Math.abs(v - 0.68f);
                    float score = (s * 1.4f) * (0.4f + brightnessWeight);
                    if (score > bestScore) {
                        bestScore = score;
                        bestColor = px;
                    }
                }
            }

            if (bestScore < 0f) {
                return fallbackColor;
            }

            Color.colorToHSV(bestColor, hsv);
            hsv[1] = Math.max(0.38f, Math.min(0.76f, hsv[1]));
            hsv[2] = Math.max(0.58f, Math.min(0.88f, hsv[2]));
            return Color.HSVToColor(hsv);
        } catch (Throwable ignored) {
            return fallbackColor;
        } finally {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
    }

    /**
     * Automatically converts blinding pure-white/light-slab Leanback banners (e.g., Netflix,
     * Play Store, YouTube) into cohesive Obsidian Spatial dark banners while preserving brand
     * colors, crisp silver-white wordmarks, and enclosed white cutouts (e.g. YouTube play icon).
     * Dark banners return untouched with zero retained bitmap overhead.
     */
    public static Drawable remapLightBannerIfNeeded(Drawable banner, int accentColor, Resources res) {
        if (banner == null) return null;
        final int w = 320;
        final int h = 180;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.WHITE);
            banner.setFilterBitmap(true);
            Rect oldBounds = new Rect(banner.getBounds());
            banner.setBounds(0, 0, w, h);
            banner.draw(c);
            banner.setBounds(oldBounds);

            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);

            int perimCount = 2 * w + 2 * (h - 2);
            int[] perimMin = new int[perimCount];
            int idx = 0;
            int whiteCount = 0;

            for (int x = 0; x < w; x++) {
                int pTop = px[x];
                int pBot = px[(h - 1) * w + x];
                whiteCount += recordPerim(pTop, perimMin, idx++);
                whiteCount += recordPerim(pBot, perimMin, idx++);
            }
            for (int y = 1; y < h - 1; y++) {
                int pLeft = px[y * w];
                int pRight = px[y * w + (w - 1)];
                whiteCount += recordPerim(pLeft, perimMin, idx++);
                whiteCount += recordPerim(pRight, perimMin, idx++);
            }

            if (whiteCount < perimCount * 0.72f) {
                bmp.recycle();
                bmp = null;
                return banner;
            }

            Arrays.sort(perimMin);
            float whiteRef = Math.max(236f, perimMin[(int) (perimCount * 0.75f)]);

            // Flood-fill from perimeter across non-saturated pixels so interior white cutouts
            // enclosed inside saturated shapes (e.g. YouTube play triangle) remain pure white.
            boolean[] exterior = new boolean[w * h];
            int[] queue = new int[w * h];
            int qHead = 0;
            int qTail = 0;

            for (int x = 0; x < w; x++) {
                int topI = x;
                int botI = (h - 1) * w + x;
                exterior[topI] = true;
                queue[qTail++] = topI;
                exterior[botI] = true;
                queue[qTail++] = botI;
            }
            for (int y = 1; y < h - 1; y++) {
                int leftI = y * w;
                int rightI = y * w + (w - 1);
                exterior[leftI] = true;
                queue[qTail++] = leftI;
                exterior[rightI] = true;
                queue[qTail++] = rightI;
            }

            while (qHead < qTail) {
                int cur = queue[qHead++];
                int cx = cur % w;
                int cy = cur / w;

                if (cx + 1 < w) qTail = tryEnqueue(cur + 1, px, exterior, queue, qTail);
                if (cx - 1 >= 0) qTail = tryEnqueue(cur - 1, px, exterior, queue, qTail);
                if (cy + 1 < h) qTail = tryEnqueue(cur + w, px, exterior, queue, qTail);
                if (cy - 1 >= 0) qTail = tryEnqueue(cur - w, px, exterior, queue, qTail);
            }

            int ar = Color.red(accentColor);
            int ag = Color.green(accentColor);
            int ab = Color.blue(accentColor);
            float cx0 = w * 0.50f;
            float cy0 = h * 0.36f;
            float invMaxR2 = 1f / ((w * 0.65f) * (w * 0.65f) + (h * 0.65f) * (h * 0.65f));
            float[] toLin = getSrgbToLinLut();
            float whiteRefLin = (float) Math.pow(whiteRef / 255.0, 2.2);

            for (int y = 0; y < h; y++) {
                float dy = y - cy0;
                int rowOff = y * w;
                for (int x = 0; x < w; x++) {
                    int i = rowOff + x;
                    if (!exterior[i]) continue;

                    int p = px[i];
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;

                    float dx = x - cx0;
                    float dist2 = Math.min(1f, (dx * dx + dy * dy) * invMaxR2);
                    float t = 1f - dist2;
                    int bgRi = Math.min(255, Math.round(13f + (7f + ar * 0.11f) * t));
                    int bgGi = Math.min(255, Math.round(16f + (8f + ag * 0.11f) * t));
                    int bgBi = Math.min(255, Math.round(23f + (11f + ab * 0.11f) * t));

                    int mn = Math.min(r, Math.min(g, b));
                    if (mn >= whiteRef - 2f) {
                        px[i] = 0xFF000000 | (bgRi << 16) | (bgGi << 8) | bgBi;
                        continue;
                    }

                    // Unmix in linear light so low-res (mdpi) diagonal anti-aliased edges
                    // never retain bright white/pink stair-step blocks on dark backgrounds
                    float lr = Math.min(1f, toLin[r] / whiteRefLin);
                    float lg = Math.min(1f, toLin[g] / whiteRefLin);
                    float lb = Math.min(1f, toLin[b] / whiteRefLin);
                    float lmn = Math.min(lr, Math.min(lg, lb));

                    float alpha = Math.min(1f, Math.max(0.001f, 1f - lmn));
                    float oneMinusA = 1f - alpha;
                    float inkLr = clamp01((lr - oneMinusA) / alpha);
                    float inkLg = clamp01((lg - oneMinusA) / alpha);
                    float inkLb = clamp01((lb - oneMinusA) / alpha);

                    int mx = Math.max(r, Math.max(g, b));
                    float srgbAlpha = Math.max(0.02f, (whiteRef - mn) / whiteRef);
                    float rawChroma = (mx - mn) / (255f * srgbAlpha);

                    float neutralW = Math.max(0f, Math.min(1f, (0.22f - rawChroma) / 0.12f));
                    if (mn > 232) {
                        neutralW *= Math.max(0f, (248f - mn) / 16f);
                    }

                    float fgLr = inkLr * (1f - neutralW) + 0.855f * neutralW;
                    float fgLg = inkLg * (1f - neutralW) + 0.895f * neutralW;
                    float fgLb = inkLb * (1f - neutralW) + 0.945f * neutralW;

                    // Use sRGB coverage for neutral text strokes so thin glyphs keep crisp weight,
                    // and linear coverage for chromatic brand logos so mdpi stair-steps vanish.
                    float baseAlpha = alpha * (1f - neutralW) + srgbAlpha * neutralW;
                    float effAlpha = Math.min(1f, baseAlpha * (1f + 0.18f * neutralW));
                    float invEffA = 1f - effAlpha;

                    int outR = linToSrgbByte(toLin[bgRi] * invEffA + fgLr * effAlpha);
                    int outG = linToSrgbByte(toLin[bgGi] * invEffA + fgLg * effAlpha);
                    int outB = linToSrgbByte(toLin[bgBi] * invEffA + fgLb * effAlpha);
                    px[i] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
                }
            }

            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return new BitmapDrawable(res, bmp);
        } catch (Throwable ignored) {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
            return banner;
        }
    }

    private static int recordPerim(int c, int[] outMin, int idx) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int mn = Math.min(r, Math.min(g, b));
        int mx = Math.max(r, Math.max(g, b));
        outMin[idx] = mn;
        return (mn > 228 && (mx - mn) < 18) ? 1 : 0;
    }

    private static int tryEnqueue(int idx, int[] px, boolean[] exterior, int[] queue, int qTail) {
        if (exterior[idx]) return qTail;
        int c = px[idx];
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int mn = Math.min(r, Math.min(g, b));
        int mx = Math.max(r, Math.max(g, b));
        // Only block at truly opaque saturated walls (mn <= 22) so every anti-aliased edge
        // pixel that still has white mixed into it gets unmixed cleanly.
        if (mn <= 22 && (mx - mn) >= 175) return qTail;
        exterior[idx] = true;
        queue[qTail++] = idx;
        return qTail;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * Builds a subtle radial tinted obsidian backdrop for icon-only or bespoke system tiles.
     */
    public static Drawable createIconTileBackdrop(int accentColor, float radiusPx) {
        int r = Color.red(accentColor);
        int g = Color.green(accentColor);
        int b = Color.blue(accentColor);

        int center = Color.rgb(
                (int) (17 + r * 0.20f),
                (int) (20 + g * 0.20f),
                (int) (28 + b * 0.20f)
        );
        int edge = 0xFF0D1017;

        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { center, edge });
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gd.setGradientCenter(0.5f, 0.35f);
        gd.setGradientRadius(radiusPx);
        return gd;
    }
}
