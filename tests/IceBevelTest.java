package com.ktc.launcher;

import java.util.Arrays;

/** Host-side optical regressions; runs with javac/java, without the Android SDK. */
public final class IceBevelTest {
    private static final int W = 160, H = 96, RADIUS = 24, WIDTH = 12;
    private static final int DARK = 0xff281c24, RUBY = 0xffd22939;

    public static void main(String[] args) {
        int[] face = new int[W * H];
        Arrays.fill(face, DARK);
        int[] flat = IceBevel.render(W, H, RADIUS, WIDTH, RUBY, face);
        checkRoll(flat, W / 2, 0, 0, 1);
        checkRoll(flat, W / 2, H - 1, 0, -1);
        checkRoll(flat, 0, H / 2, 1, 0);
        checkRoll(flat, W - 1, H / 2, -1, 0);

        // A printed white rule just inside the bevel must move, not acquire a blurred copy.
        Arrays.fill(face, 6 * W, 7 * W, 0xffffffff);
        int[] ruled = IceBevel.render(W, H, RADIUS, WIDTH, RUBY, face);
        int bent = 4 * W + W / 2, original = 6 * W + W / 2;
        require(luma(ruled[bent], DARK) > luma(flat[bent], DARK) + 70, "rule did not bend outward");
        require(Math.abs(luma(ruled[original], face[original]) - luma(flat[original], DARK)) <= 2,
                "rule was duplicated at its original position");
        for (int y = RADIUS; y < H - RADIUS; y++) {
            for (int x = WIDTH; x < W - WIDTH; x++) {
                require(ruled[y * W + x] == 0, "flat face was repainted");
            }
        }
    }

    private static void checkRoll(int[] rim, int x, int y, int dx, int dy) {
        int peak = luma(rim[y * W + x], DARK);
        require(peak > luma(0, DARK) + 45, "no bright, well-defined silhouette");
        int last = peak;
        for (int d = 1; d <= WIDTH; d++) {
            int next = luma(rim[(y + dy * d) * W + x + dx * d], DARK);
            require(next <= last + 1, "second highlight inside the lip");
            last = next;
        }
        require(last == luma(0, DARK), "bevel did not meet the flat face");
    }

    private static int luma(int overlay, int base) {
        int a = overlay >>> 24, luminance = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int c = (((overlay >> shift) & 255) * a + ((base >> shift) & 255) * (255 - a) + 127) / 255;
            luminance += c * (shift == 0 ? 11 : shift == 8 ? 59 : 30);
        }
        return luminance / 100;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
