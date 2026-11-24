package com.ktc.launcher;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.PathInterpolator;

/**
 * Lightweight, zero-blur GPU radial ambient backlight for the Obsidian Spatial launcher.
 *
 * Draws a deep OLED-grade obsidian base with two subtle radial luminance washes that
 * cross-fade smoothly toward the focused tile's accent color and screen position.
 */
public final class AmbientBackdropDrawable extends Drawable {

    public static final int DEFAULT_ACCENT = 0xFF6B8CC7;

    private static final PathInterpolator EASE_OUT = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private final Paint basePaint = new Paint(Paint.DITHER_FLAG);
    private final Paint topWashPaint = new Paint(Paint.DITHER_FLAG);
    private final Paint spotWashPaint = new Paint(Paint.DITHER_FLAG);

    private int currentColor = DEFAULT_ACCENT;
    private float focusNormX = 0.20f;
    private float focusNormY = 0.30f;

    private boolean baseDirty = true;
    private boolean washDirty = true;
    private int lastW = 0;
    private int lastH = 0;

    private ValueAnimator animator;

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        baseDirty = true;
        washDirty = true;
    }

    /**
     * Smoothly transitions the ambient backlight color and subtle spotlight position.
     */
    public void transitionTo(int targetColor, float targetNormX, float targetNormY) {
        final float clampedX = Math.max(0.08f, Math.min(0.92f, targetNormX));
        final float clampedY = Math.max(0.05f, Math.min(0.90f, targetNormY));

        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        final int startColor = currentColor;
        final float startX = focusNormX;
        final float startY = focusNormY;

        if (startColor == targetColor
                && Math.abs(startX - clampedX) < 0.01f
                && Math.abs(startY - clampedY) < 0.01f) {
            return;
        }

        final int sr = Color.red(startColor);
        final int sg = Color.green(startColor);
        final int sb = Color.blue(startColor);
        final int tr = Color.red(targetColor);
        final int tg = Color.green(targetColor);
        final int tb = Color.blue(targetColor);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(380L);
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
                washDirty = true;
                invalidateSelf();
            }
        });
        animator.start();
    }

    public void setImmediate(int color, float normX, float normY) {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        currentColor = color;
        focusNormX = Math.max(0.08f, Math.min(0.92f, normX));
        focusNormY = Math.max(0.05f, Math.min(0.90f, normY));
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
                    new int[] { 0xFF0C0E14, 0xFF07080B, 0xFF040507 },
                    new float[] { 0f, 0.55f, 1f },
                    Shader.TileMode.CLAMP
            ));
            baseDirty = false;
            washDirty = true;
        }

        if (washDirty) {
            int r = Color.red(currentColor);
            int g = Color.green(currentColor);
            int bl = Color.blue(currentColor);

            // Primary cinema overhead ambient wash
            float topRadius = Math.max(w, h) * 0.80f;
            topWashPaint.setShader(new RadialGradient(
                    w * 0.50f, -h * 0.12f, topRadius,
                    new int[] {
                            Color.argb(0x36, r, g, bl),
                            Color.argb(0x14, r, g, bl),
                            Color.argb(0x00, r, g, bl)
                    },
                    new float[] { 0f, 0.48f, 1f },
                    Shader.TileMode.CLAMP
            ));

            // Secondary subtle spatial spotlight following focused element
            float spotRadius = w * 0.44f;
            spotWashPaint.setShader(new RadialGradient(
                    w * focusNormX, h * focusNormY, spotRadius,
                    new int[] {
                            Color.argb(0x22, r, g, bl),
                            Color.argb(0x09, r, g, bl),
                            Color.argb(0x00, r, g, bl)
                    },
                    new float[] { 0f, 0.52f, 1f },
                    Shader.TileMode.CLAMP
            ));
            washDirty = false;
        }

        canvas.drawRect(b, basePaint);
        canvas.drawRect(b, topWashPaint);
        canvas.drawRect(b, spotWashPaint);
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
                    // Skip near-black and near-white neutral pixels
                    if (v < 0.16f || (v > 0.93f && s < 0.14f) || s < 0.12f) continue;

                    // Prefer rich mid-bright brand colors over dark muddy tones
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

            // Clamp into a refined, non-garish ambient range
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
     * Builds a subtle radial tinted obsidian backdrop for icon-only apps lacking a 16:9 banner.
     */
    public static Drawable createIconTileBackdrop(int accentColor, float radiusPx) {
        int r = Color.red(accentColor);
        int g = Color.green(accentColor);
        int b = Color.blue(accentColor);

        int center = Color.rgb(
                (int) (18 + r * 0.22f),
                (int) (21 + g * 0.22f),
                (int) (29 + b * 0.22f)
        );
        int edge = 0xFF0F1219;

        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { center, edge });
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gd.setGradientCenter(0.5f, 0.35f);
        gd.setGradientRadius(radiusPx);
        return gd;
    }
}
