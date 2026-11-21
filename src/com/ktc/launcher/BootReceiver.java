package com.ktc.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * Automatically switches the KTC A32Q8 monitor to DisplayPort (or the configured default input)
 * shortly after Android TV finishes booting, so the monitor never gets stuck on the TV menu
 * when powering on your workstation.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        if (!InputManagerHelper.isAutoBootDpEnabled(context)) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        final String targetInputId = InputManagerHelper.getDefaultInputId(context);

        // Give Realtek's DPTvInputService ~2.5s to register its hardware passthrough session
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    InputManagerHelper.switchToInput(context.getApplicationContext(), targetInputId);
                } finally {
                    pendingResult.finish();
                }
            }
        }, 2500L);
    }
}
