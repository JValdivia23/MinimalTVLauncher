package com.ktc.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Discovers real hardware passthrough inputs (DisplayPort, Type-C, HDMI) on the KTC A32Q8
 * Realtek RTD2851F board and launches them directly via com.realtek.tv/.MainActivity.
 */
public final class InputManagerHelper {

    public static final String PREFS_NAME = "ktc_launcher_prefs";
    public static final String KEY_AUTO_BOOT_DP = "auto_boot_dp";
    public static final String KEY_DEFAULT_INPUT_ID = "default_input_id";

    // Known KTC A32Q8 Realtek passthrough IDs (also dynamically discovered at runtime)
    public static final String DEFAULT_DP_ID =
            "com.realtek.tv.passthrough/.dpinput.DPTvInputService/HW168624384";
    public static final String DEFAULT_USBC_ID =
            "com.realtek.tv.passthrough/.dpinput.DPTvInputService/HW168624128";
    public static final String DEFAULT_HDMI_ID =
            "com.realtek.tv.passthrough/.hdmiinput.HDMITvInputService/HW151519232";

    public static final class InputItem {
        public final String inputId;
        public final String label;
        public final String subtitle;
        public final int iconRes;
        public final boolean active;
        public final int sortOrder;

        public InputItem(String inputId, String label, String subtitle, int iconRes, boolean active, int sortOrder) {
            this.inputId = inputId;
            this.label = label;
            this.subtitle = subtitle;
            this.iconRes = iconRes;
            this.active = active;
            this.sortOrder = sortOrder;
        }
    }

    private InputManagerHelper() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isAutoBootDpEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_BOOT_DP, true);
    }

    public static void setAutoBootDpEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_BOOT_DP, enabled).apply();
    }

    public static String getDefaultInputId(Context ctx) {
        return prefs(ctx).getString(KEY_DEFAULT_INPUT_ID, DEFAULT_DP_ID);
    }

    public static void setDefaultInputId(Context ctx, String inputId) {
        prefs(ctx).edit().putString(KEY_DEFAULT_INPUT_ID, inputId).apply();
    }

    public static String getBootTargetShortName(Context ctx) {
        if (!isAutoBootDpEnabled(ctx)) {
            return "OFF";
        }
        String id = getDefaultInputId(ctx);
        if (id == null || id.isEmpty()) return "DP";
        if (id.contains("HW168624128")) return "USB-C";
        if (id.contains("hdmiinput") || id.contains("HW151519232")) return "HDMI 1";
        if (id.contains("HW168624384") || id.contains("dpinput")) return "DP";
        return "Input";
    }

    /**
     * Cycles the boot target cleanly: DP -> USB-C -> HDMI 1 -> OFF -> DP.
     */
    public static String cycleBootTarget(Context ctx) {
        boolean enabled = isAutoBootDpEnabled(ctx);
        String currentId = getDefaultInputId(ctx);

        if (!enabled) {
            setDefaultInputId(ctx, DEFAULT_DP_ID);
            setAutoBootDpEnabled(ctx, true);
            return "DP";
        } else if (currentId.contains("HW168624384")) {
            setDefaultInputId(ctx, DEFAULT_USBC_ID);
            setAutoBootDpEnabled(ctx, true);
            return "USB-C";
        } else if (currentId.contains("HW168624128")) {
            setDefaultInputId(ctx, DEFAULT_HDMI_ID);
            setAutoBootDpEnabled(ctx, true);
            return "HDMI 1";
        } else {
            setAutoBootDpEnabled(ctx, false);
            return "OFF";
        }
    }

    /**
     * Queries Android's TvInputManager for hardware passthrough inputs and orders DP first,
     * followed by USB-C, then HDMI.
     */
    public static List<InputItem> loadHardwareInputs(Context ctx) {
        List<InputItem> result = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) {
                List<TvInputInfo> list = tim.getTvInputList();
                if (list != null) {
                    for (TvInputInfo info : list) {
                        if (!info.isPassthroughInput()) continue;
                        String id = info.getId();
                        if (id == null || !id.contains("HW")) continue;

                        CharSequence rawLabelCs = info.loadLabel(ctx);
                        String rawLabel = rawLabelCs != null ? rawLabelCs.toString().trim() : "";
                        int state = tim.getInputState(id);
                        boolean connected = (state == TvInputManager.INPUT_STATE_CONNECTED);

                        String displayLabel;
                        String subtitle;
                        int iconRes;
                        int order;

                        String upper = rawLabel.toUpperCase();
                        if (id.contains("HW168624384") || upper.equals("DP") || upper.startsWith("DP ")) {
                            displayLabel = "DisplayPort";
                            subtitle = connected ? "DP 1.4 • Active" : "DP 1.4";
                            iconRes = R.drawable.ic_dp;
                            order = 0;
                        } else if (id.contains("HW168624128") || upper.contains("TYPE-C") || upper.contains("USB")) {
                            displayLabel = "USB-C";
                            subtitle = connected ? "DP Alt • Active" : "Type-C 65W";
                            iconRes = R.drawable.ic_usbc;
                            order = 1;
                        } else if (id.contains("hdmiinput") || upper.contains("HDMI")) {
                            displayLabel = rawLabel.equalsIgnoreCase("HDMI1") ? "HDMI 1" : (rawLabel.isEmpty() ? "HDMI 1" : rawLabel);
                            subtitle = connected ? "HDMI 2.1 • Active" : "HDMI 2.1";
                            iconRes = R.drawable.ic_hdmi;
                            order = 2;
                        } else {
                            displayLabel = rawLabel.isEmpty() ? id : rawLabel;
                            subtitle = connected ? "Active" : "Input";
                            iconRes = R.drawable.ic_hdmi;
                            order = 3;
                        }
                        result.add(new InputItem(id, displayLabel, subtitle, iconRes, connected, order));
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback if TvInputManager isn't ready yet during early boot
        if (result.isEmpty()) {
            result.add(new InputItem(DEFAULT_DP_ID, "DisplayPort", "DP 1.4", R.drawable.ic_dp, false, 0));
            result.add(new InputItem(DEFAULT_USBC_ID, "USB-C", "Type-C 65W", R.drawable.ic_usbc, false, 1));
            result.add(new InputItem(DEFAULT_HDMI_ID, "HDMI 1", "HDMI 2.1", R.drawable.ic_hdmi, false, 2));
        }

        Collections.sort(result, new Comparator<InputItem>() {
            @Override
            public int compare(InputItem a, InputItem b) {
                return Integer.compare(a.sortOrder, b.sortOrder);
            }
        });
        return result;
    }

    public static boolean switchToInput(Context ctx, String inputId) {
        try {
            Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setClassName("com.realtek.tv", "com.realtek.tv.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            try {
                Uri uri = Uri.parse("content://android.media.tv/passthrough/" + Uri.encode(inputId));
                Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(fallback);
                return true;
            } catch (Throwable t2) {
                Toast.makeText(ctx, "Failed to switch input: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                return false;
            }
        }
    }

    public static void openNativeInputPopup(Context ctx) {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.realtek.tv", "com.realtek.tv.SelectInputActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(ctx, "Input menu unavailable", Toast.LENGTH_SHORT).show();
        }
    }
}
