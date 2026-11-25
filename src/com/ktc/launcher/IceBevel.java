package com.ktc.launcher;

/** Bakes the optics of a quarter-round lip. All distances are in tile pixels. */
final class IceBevel {
    private static final float AIR_TO_ICE = 1f / 1.31f;

    private IceBevel() {}

    // Transparent output means "show the live face", never "erase the artwork".
    static int[] render(int w, int h, float radius, float width, int accent, int[] face) {
        int[] rim = new int[w * h];
        radius = Math.min(radius, Math.min(w, h) * 0.5f);
        width = Math.min(width, radius);
        if (width <= 0f) return rim;

        int r = (accent >> 16) & 255, g = (accent >> 8) & 255, b = accent & 255;
        float exposure = 255f / Math.max(1, Math.max(r, Math.max(g, b)));
        float red = r * exposure, green = g * exposure, blue = b * exposure;
        int margin = (int) Math.ceil(radius + 1f);
        for (int y = 0; y < h; y++) {
            float cy = y + 0.5f - h * 0.5f;
            float qy = Math.abs(cy) - h * 0.5f + radius;
            boolean sideStrips = y >= margin && y < h - margin && w > 2 * margin;
            int count = sideStrips ? 2 * margin : w;
            for (int i = 0; i < count; i++) {
                int x = sideStrips && i >= margin ? i + w - 2 * margin : i;
                float cx = x + 0.5f - w * 0.5f;
                float qx = Math.abs(cx) - w * 0.5f + radius;

                // Inset from the silhouette and outward direction of its nearest point.
                // On a corner the direction rotates continuously between the two sides.
                float inset, nx, ny;
                if (qx > 0f && qy > 0f) {
                    float len = (float) Math.sqrt(qx * qx + qy * qy);
                    inset = radius - len;
                    nx = Math.copySign(qx / len, cx);
                    ny = Math.copySign(qy / len, cy);
                } else {
                    inset = radius - Math.max(qx, qy);
                    nx = qx > qy ? Math.signum(cx) : 0f;
                    ny = qx > qy ? 0f : Math.signum(cy);
                }
                // Shade slightly past the arc; the view's outline antialiases it exactly once.
                if (inset < -1.5f || inset >= width) continue;
                float side = 1f - Math.max(0f, inset) / width;  // sin(theta)
                float front = (float) Math.sqrt(1f - side * side);  // cos(theta)

                // A rear area light supplies the accent, an overhead catchlight supplies the
                // silver glint. Both peak at the silhouette and decay down the SAME curve.
                float lx = 2f * cx / w, ly = 2f * cy / h - 0.09f;
                float aim = Math.max(0f, (nx * lx + ny * ly)
                        / (float) Math.sqrt(Math.max(0.001f, lx * lx + ly * ly)));
                float up = Math.max(0f, -ny);
                float turn = 2f * Math.abs(nx * ny);
                float grazing = 1f - front;
                float peak = Math.min(1f, 0.60f + 0.23f * aim + 0.12f * up + 0.13f * turn);
                float light = peak * (0.74f * side * side + 0.26f * grazing);
                float white = grazing * (0.12f + 0.48f * up + 0.22f * turn);

                // Trace the eye ray through that same normal (Snell, air -> ice) to the
                // printed artwork below. The material is thinner at the lip than at the face.
                int ink = 0;
                float transmitted = 0f;
                if (face != null) {
                    float bend = (float) Math.sqrt(1f - AIR_TO_ICE * AIR_TO_ICE * side * side)
                            - AIR_TO_ICE * front;
                    float shift = width * (0.45f + 0.75f * front)
                            * side * bend / (AIR_TO_ICE + front * bend);
                    ink = sample(face, w, h, x - nx * shift, y - ny * shift);
                    // Hand subpixel displacements back to the live art at the flat tangent.
                    transmitted = (1f - light) * Math.min(1f, shift) * ((ink >>> 24) / 255f);
                }
                float alpha = light + transmitted;
                int a = Math.round(255f * alpha);
                if (a == 0) continue;
                rim[y * w + x] = a << 24
                        | channel(red, white, light, (ink >> 16) & 255, transmitted, alpha) << 16
                        | channel(green, white, light, (ink >> 8) & 255, transmitted, alpha) << 8
                        | channel(blue, white, light, ink & 255, transmitted, alpha);
            }
        }
        return rim;
    }

    private static int channel(float accent, float white, float light, int ink,
                               float transmitted, float alpha) {
        float reflection = accent + (255f - accent) * white;
        return Math.min(255, Math.round((reflection * light + ink * transmitted) / alpha));
    }

    // One bilinear texture lookup at the refracted coordinate, not a blur kernel.
    private static int sample(int[] pixels, int w, int h, float x, float y) {
        x = Math.max(0f, Math.min(w - 1f, x));
        y = Math.max(0f, Math.min(h - 1f, y));
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        int a = pixels[y0 * w + x0], b = pixels[y0 * w + x1];
        int c = pixels[y1 * w + x0], d = pixels[y1 * w + x1];
        if (a == b && a == c && a == d) return a;
        float fx = x - x0, fy = y - y0;
        int color = 0;
        for (int bit = 0; bit <= 24; bit += 8) {
            int v = (a >>> bit) & 255, u = (c >>> bit) & 255;
            float top = v + (((b >>> bit) & 255) - v) * fx;
            float bottom = u + (((d >>> bit) & 255) - u) * fx;
            color |= Math.round(top + (bottom - top) * fy) << bit;
        }
        return color;
    }
}
