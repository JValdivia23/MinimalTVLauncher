package com.ktc.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Fires on AlarmManager schedule to show the 1-minute sleep warning or suspend the monitor.
 */
public class SleepAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        if (SleepTimerHelper.ACTION_SLEEP_WARNING.equals(action)) {
            Toast.makeText(
                    context.getApplicationContext(),
                    "Sleep timer: monitor will sleep in 1 minute",
                    Toast.LENGTH_LONG
            ).show();
        } else if (SleepTimerHelper.ACTION_SLEEP_TRIGGER.equals(action)) {
            SleepTimerHelper.triggerHardwareSleep(context.getApplicationContext());
        }
    }
}
