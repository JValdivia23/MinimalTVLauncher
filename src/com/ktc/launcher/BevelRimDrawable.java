package com.ktc.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

/**
 * Physically coherent, zero-offset 3D bevel rim for spatial cards and 16:9 app tiles.
 *
 * Lighting model:
 * - Rest state: Lit strictly from 12-o'clock straight overhead (left-right symmetric).
 *   Top horizontal rim catches gentle overhead room light, vertical side walls taper neutrally,
 *   and the bottom horizontal rim sits in grounded underside shadow.
 * - Focused state: A sharp silhouette encloses a quarter-round ice bevel. Its surface
 *   refracts the backplate and catches the accent backlight most strongly at grazing angles.
 */
public final class BevelRimDrawable extends Drawable {

    private static final long FOCUS_ANIM_MS = 195L;
    private static final PathInterpolator FOCUS_EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private final float cornerRadiusPx;
    private final float restThicknessPx;
    private final float focusedThicknessPx;
    private final int accentColor;
    private final View edgeArtwork;

    private final Paint restPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint focusCachePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Path restPath = new Path();

    private boolean focused = false;
    private float focusProgress = 0f;
    private ValueAnimator focusAnimator;
    private Bitmap focusCache;

    private boolean restDirty = true;
    private int masterAlpha = 255;

    public BevelRimDrawable(
            float cornerRadiusPx,
            float restThicknessPx,
            float focusedThicknessPx,
            int accentColor,
            View edgeArtwork
    ) {
        this.cornerRadiusPx = cornerRadiusPx;
        this.restThicknessPx = restThicknessPx;
        this.focusedThicknessPx = focusedThicknessPx;
        this.accentColor = accentColor;
        this.edgeArtwork = edgeArtwork;
        restPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean nowFocused = false;
        if (state != null) {
            for (int s : state) {
                if (s == android.R.attr.state_focused) {
                    nowFocused = true;
                    break;
                }
            }
        }
        if (nowFocused == focused) return false;

        focused = nowFocused;
        float target = focused ? 1f : 0f;
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }
        float distance = Math.abs(target - focusProgress);
        if (distance < 0.001f) {
            setFocusProgress(target);
            if (!focused) releaseFocusCache();
        } else {
            focusAnimator = ValueAnimator.ofFloat(focusProgress, target);
            focusAnimator.setDuration(Math.max(1L, Math.round(FOCUS_ANIM_MS * distance)));
            focusAnimator.setInterpolator(FOCUS_EASE);
            focusAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    setFocusProgress((Float) animation.getAnimatedValue());
                }
            });
            focusAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // A cancelled focus-in must not release the cache needed by its fade-out.
                    if (target == 0f && !focused && focusProgress <= 0.001f) {
                        releaseFocusCache();
                    }
                }
            });
            focusAnimator.start();
        }
        invalidateSelf();
        return true;
    }

    @Override
    public void jumpToCurrentState() {
        super.jumpToCurrentState();
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }
        setFocusProgress(focused ? 1f : 0f);
        if (!focused) {
            releaseFocusCache();
        }
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        restDirty = true;
    }

    private void rebuildRest(int w, int h) {
        restPath.reset();
        restPath.setFillType(Path.FillType.EVEN_ODD);
        // Preserve the original zero-gap resting bevel and its exact inner corner radius.
        restPath.addRoundRect(
                -1f, -1f, w + 1f, h + 1f,
                cornerRadiusPx + 1f, cornerRadiusPx + 1f, Path.Direction.CW);
        float innerR = Math.max(0f, cornerRadiusPx - restThicknessPx);
        restPath.addRoundRect(
                restThicknessPx, restThicknessPx,
                w - restThicknessPx, h - restThicknessPx,
                innerR, innerR, Path.Direction.CW);

        // Strictly vertical 12-o'clock overhead ambient light (100% left-right symmetric).
        restPaint.setShader(new LinearGradient(
                0f, 0f, 0f, h,
                new int[] {
                        0x3CE2E8F0,
                        0x0AE2E8F0,
                        0x00E2E8F0,
                        0x00000000,
                        0x18000000,
                        0x72000000
                },
                new float[] { 0f, 0.20f, 0.32f, 0.68f, 0.78f, 1f },
                Shader.TileMode.CLAMP
        ));
        restDirty = false;
    }

    private void ensureFocusCache(int w, int h) {
        if (focusCache != null && focusCache.getWidth() == w && focusCache.getHeight() == h) return;
        int[] rim = IceBevel.render(w, h, cornerRadiusPx, focusedThicknessPx, accentColor, readFace(w, h));
        focusCache = Bitmap.createBitmap(rim, w, h, Bitmap.Config.ARGB_8888);
        focusCache.setDensity(Bitmap.DENSITY_NONE);
        focusCachePaint.setShader(new BitmapShader(focusCache, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    // Only the background and this full-bleed layer reach the lip. Capture them in tile
    // coordinates once; the live logo, text and flat face are never replaced or rescaled.
    private int[] readFace(int w, int h) {
        if (!(getCallback() instanceof View)) return null;
        View host = (View) getCallback();
        Bitmap plate = Bitmap.createBitmap(host.getResources().getDisplayMetrics(),
                w, h, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(plate);
            Drawable background = host.getBackground();
            if (background != null) background.draw(canvas);
            if (edgeArtwork != null) {
                canvas.translate(edgeArtwork.getLeft(), edgeArtwork.getTop());
                canvas.clipRect(0, 0, edgeArtwork.getWidth(), edgeArtwork.getHeight());
                drawEdgeArtwork(canvas);
            }
            int[] pixels = new int[w * h];
            plate.getPixels(pixels, 0, w, 0, 0, w, h);
            return pixels;
        } catch (IllegalArgumentException unsupportedSoftwareDrawable) {
            // An opaque vendor drawable may only support a hardware canvas. Its live image
            // still gets the same lighting, without trying to read its pixels.
            return null;
        } finally {
            plate.recycle();
        }
    }

    private void drawEdgeArtwork(Canvas canvas) {
        if (edgeArtwork instanceof ImageView) {
            ImageView image = (ImageView) edgeArtwork;
            Drawable art = image.getDrawable();
            if (art instanceof BitmapDrawable) {
                BitmapDrawable banner = (BitmapDrawable) art;
                Bitmap bitmap = banner.getBitmap();
                if (bitmap != null && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                    Bitmap readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    if (readable != null) {
                        try {
                            canvas.translate(image.getPaddingLeft(), image.getPaddingTop());
                            canvas.concat(image.getImageMatrix());
                            canvas.drawBitmap(readable, null, art.getBounds(), banner.getPaint());
                        } finally {
                            readable.recycle();
                        }
                        return;
                    }
                }
            }
        }
        edgeArtwork.draw(canvas);
    }

    private void setFocusProgress(float progress) {
        focusProgress = progress;
        invalidateSelf();
    }

    private void releaseFocusCache() {
        // A recorded hardware frame can still reference the bitmap.
        focusCachePaint.setShader(null);
        focusCache = null;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0 || masterAlpha <= 0) return;

        if (restDirty) {
            rebuildRest(w, h);
        }

        int save = canvas.save();
        canvas.translate(b.left, b.top);
        int restAlpha = Math.round(masterAlpha * (1f - focusProgress));
        if (restAlpha > 0) {
            restPaint.setAlpha(restAlpha);
            canvas.drawPath(restPath, restPaint);
        }

        int focusAlpha = Math.round(masterAlpha * focusProgress);
        if (focusAlpha > 0) {
            ensureFocusCache(w, h);
            focusCachePaint.setAlpha(focusAlpha);
            // Cover fractional edge pixels of the scaled view. The outline alone cuts the
            // silhouette; a second cut at the texture bounds would expose a dark hairline.
            canvas.drawRect(-1f, -1f, w + 1f, h + 1f, focusCachePaint);
        }
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        masterAlpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        restPaint.setColorFilter(colorFilter);
        focusCachePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
