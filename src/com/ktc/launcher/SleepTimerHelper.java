package com.ktc.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

/**
 * Manages the hardware-native Sleep Timer using AlarmManager + Realtek's built-in
 * SleepTimerReceiver (`sleeptimer.SleepTimerRecevier`), so the timer works reliably
 * even after switching to Netflix, YouTube, or DisplayPort.
 */
public final class SleepTimerHelper {

    public static final String KEY_SLEEP_END_ELAPSED = "sleep_end_elapsed_ms";
    public static final String KEY_SLEEP_PRESET_IDX = "sleep_preset_idx";

    public static final String ACTION_SLEEP_WARNING = "com.ktc.launcher.ACTION_SLEEP_WARNING";
    public static final String ACTION_SLEEP_TRIGGER = "com.ktc.launcher.ACTION_SLEEP_TRIGGER";

    // Presets in minutes: 0 = OFF
    public static final int[] PRESETS_MINUTES = { 0, 15, 30, 45, 60, 90, 120 };

    private static final int REQ_WARN = 9001;
    private static final int REQ_SLEEP = 9002;

    private SleepTimerHelper() {}

    private static SharedPreferences prefs(Context ctx) {
        return InputManagerHelper.prefs(ctx);
    }

    /**
     * Returns remaining milliseconds until sleep, or 0 if no active timer.
     */
    public static long getRemainingMs(Context ctx) {
        long end = prefs(ctx).getLong(KEY_SLEEP_END_ELAPSED, 0L);
        if (end <= 0L) return 0L;
        long rem = end - SystemClock.elapsedRealtime();
        if (rem <= 0L) {
            clearState(ctx);
            return 0L;
        }
        return rem;
    }

    public static boolean isActive(Context ctx) {
        return getRemainingMs(ctx) > 0L;
    }

    /**
     * Cycles presets: OFF -> 15m -> 30m -> 45m -> 60m -> 90m -> 120m -> OFF.
     * Returns the newly selected duration in minutes (0 = OFF).
     */
    public static int cyclePreset(Context ctx) {
        int nextIdx;
        if (!isActive(ctx)) {
            nextIdx = 1; // 15m
        } else {
            int curIdx = prefs(ctx).getInt(KEY_SLEEP_PRESET_IDX, 0);
            nextIdx = (curIdx + 1) % PRESETS_MINUTES.length;
        }

        int minutes = PRESETS_MINUTES[nextIdx];
        if (minutes <= 0) {
            cancel(ctx);
            return 0;
        }
        scheduleMinutes(ctx, minutes, nextIdx);
        return minutes;
    }

    public static void scheduleMinutes(Context ctx, int minutes, int presetIdx) {
        cancel(ctx);
        long now = SystemClock.elapsedRealtime();
        long delayMs = minutes * 60_000L;
        long triggerAt = now + delayMs;

        prefs(ctx).edit()
                .putLong(KEY_SLEEP_END_ELAPSED, triggerAt)
                .putInt(KEY_SLEEP_PRESET_IDX, presetIdx)
                .apply();

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        // 1-minute warning toast (if timer > 1 min)
        if (delayMs > 60_000L) {
            setExactAlarm(am, triggerAt - 60_000L, buildPendingIntent(ctx, ACTION_SLEEP_WARNING, REQ_WARN));
        }

        // Actual sleep trigger
        setExactAlarm(am, triggerAt, buildPendingIntent(ctx, ACTION_SLEEP_TRIGGER, REQ_SLEEP));
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(buildPendingIntent(ctx, ACTION_SLEEP_WARNING, REQ_WARN));
            am.cancel(buildPendingIntent(ctx, ACTION_SLEEP_TRIGGER, REQ_SLEEP));
        }
        clearState(ctx);
    }

    public static void clearState(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_SLEEP_END_ELAPSED)
                .putInt(KEY_SLEEP_PRESET_IDX, 0)
                .apply();
    }

    public static String formatPillLabel(Context ctx) {
        long remMs = getRemainingMs(ctx);
        if (remMs <= 0L) {
            return "Zzz";
        }
        long totalSec = (remMs + 999L) / 1000L;
        if (totalSec < 60L) {
            return String.format("0:%02d", totalSec);
        }
        long mins = (totalSec + 59L) / 60L;
        return mins + "m";
    }

    /**
     * Triggers hardware-native suspend on the KTC A32Q8 Realtek RTD2851F board.
     */
    public static void triggerHardwareSleep(Context ctx) {
        clearState(ctx);
        try {
            // Primary: Realtek's built-in SleepTimerReceiver (sets ForceSuspend + PowerManager.goToSleep)
            Intent rtkSleep = new Intent("sleeptimer.SleepTimerRecevier");
            rtkSleep.setPackage("com.realtek.menu");
            rtkSleep.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(rtkSleep);
        } catch (Throwable ignored) {
        }
        // Fallback: also broadcast without explicit package in case OEM firmware differs
        try {
            Intent generic = new Intent("sleeptimer.SleepTimerRecevier");
            ctx.sendBroadcast(generic);
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent buildPendingIntent(Context ctx, String action, int requestCode) {
        Intent i = new Intent(ctx, SleepAlarmReceiver.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, requestCode, i, flags);
    }

    private static void setExactAlarm(AlarmManager am, long triggerAtElapsed, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi);
            }
        } catch (SecurityException se) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi);
        }
    }
}
