package com.ktc.launcher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Physically coherent, zero-offset 3D bevel rim for spatial cards and 16:9 app tiles.
 *
 * Lighting model:
 * - Rest state: Lit strictly from 12-o'clock straight overhead (left-right symmetric).
 *   Top horizontal rim catches gentle overhead room light, vertical side walls taper neutrally,
 *   and the bottom horizontal rim sits in grounded underside shadow.
 * - Focused state: Illuminated from behind by the element's radial accent backlight + overhead
 *   catchlight. Forms a continuous, 360-degree left-right symmetric translucent rim tinted with
 *   the element's own accentColor so edge brightness and hue couple directly to the backdrop glow.
 */
public final class BevelRimDrawable extends Drawable {

    private final float cornerRadiusPx;
    private final float restThicknessPx;
    private final float focusedThicknessPx;
    private final int accentColor;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Path rimPath = new Path();

    private boolean focused = false;
    private boolean dirty = true;
    private int lastW = 0;
    private int lastH = 0;

    public BevelRimDrawable(
            float cornerRadiusPx,
            float restThicknessPx,
            float focusedThicknessPx,
            int accentColor
    ) {
        this.cornerRadiusPx = cornerRadiusPx;
        this.restThicknessPx = restThicknessPx;
        this.focusedThicknessPx = focusedThicknessPx;
        this.accentColor = accentColor;
        paint.setStyle(Paint.Style.FILL);
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
        if (nowFocused != focused) {
            focused = nowFocused;
            dirty = true;
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        dirty = true;
    }

    private static int mixWithWhite(int color, float whiteWeight, int alpha) {
        int r = Math.min(255, Math.round(Color.red(color) * (1f - whiteWeight) + 255f * whiteWeight));
        int g = Math.min(255, Math.round(Color.green(color) * (1f - whiteWeight) + 255f * whiteWeight));
        int b = Math.min(255, Math.round(Color.blue(color) * (1f - whiteWeight) + 255f * whiteWeight));
        return Color.argb(alpha, r, g, b);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        if (dirty || w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            float t = focused ? focusedThicknessPx : restThicknessPx;
            float innerR = Math.max(0f, cornerRadiusPx - t);

            rimPath.reset();
            rimPath.setFillType(Path.FillType.EVEN_ODD);
            // Extend 1px beyond bounds so the View's hardware round-rect outline clip forms the
            // exact outer edge with zero subpixel gap or leaked artwork outside the bevel.
            rimPath.addRoundRect(-1f, -1f, w + 1f, h + 1f, cornerRadiusPx + 1f, cornerRadiusPx + 1f, Path.Direction.CW);
            rimPath.addRoundRect(t, t, w - t, h - t, innerR, innerR, Path.Direction.CW);

            if (focused) {
                // Continuous, left-right symmetric backlit rim infused with accentColor:
                // - Top edge combines backlight + 12-o'clock overhead specular highlight
                // - Side walls carry smooth translucent accent glow
                // - Bottom edge couples into the glowing accent aura & spot-shadow below
                int topSpec = mixWithWhite(accentColor, 0.58f, 0xEC);
                int upperSide = mixWithWhite(accentColor, 0.30f, 0xB8);
                int lowerSide = mixWithWhite(accentColor, 0.24f, 0xAC);
                int bottomGlow = mixWithWhite(accentColor, 0.38f, 0xD4);

                paint.setShader(new LinearGradient(
                        0f, 0f, 0f, h,
                        new int[] { topSpec, upperSide, lowerSide, bottomGlow },
                        new float[] { 0f, 0.28f, 0.72f, 1f },
                        Shader.TileMode.CLAMP
                ));
            } else {
                // Strictly vertical 12-o'clock overhead ambient light (100% left-right symmetric):
                // - Gentle cool catchlight across the top horizontal edge (0 -> 0.22)
                // - Neutral unlit vertical side walls (0.28 -> 0.72)
                // - Grounded underside shadow across the bottom horizontal edge (0.76 -> 1.0)
                paint.setShader(new LinearGradient(
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
            }
            dirty = false;
        }

        canvas.drawPath(rimPath, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
