package com.ktc.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ultra-lightweight, zero-bloat open-source launcher built specifically for the
 * KTC A32Q8 (Realtek RTD2851F) Smart Monitor.
 *
 * Features:
 * - Native DisplayPort (default focus), USB-C, and HDMI 1 hardware input cards
 * - Boot target cycle pill (DP -> USB-C -> HDMI 1 -> OFF) + long-press on any input card
 * - Clean 5-column TV apps grid with rounded banners, zero clipping, and smooth D-pad focus
 * - Live clock, direct Android Settings shortcut, zero telemetry, ~38 MB total PSS footprint
 */
public class MainActivity extends Activity {

    private static boolean coldBootAutoSwitchAttempted = false;

    private LinearLayout inputsRow;
    private LinearLayout appsGridContainer;
    private TextView clockView;
    private TextView dateView;
    private TextView autoBootButton;
    private TextView settingsButton;
    private View firstInputCard;

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            clockHandler.postDelayed(this, 15000L);
        }
    };

    private final BroadcastReceiver packageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshAppsGrid();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshInputsRow();
        refreshAppsGrid();
        maybeAutoSwitchOnColdBoot();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageChangeReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
        refreshInputsRow();
        if (firstInputCard != null) {
            firstInputCard.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(packageChangeReceiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        // Pressing Back on home focuses DisplayPort card instead of exiting launcher
        if (firstInputCard != null) {
            firstInputCard.requestFocus();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT) {
            InputManagerHelper.openNativeInputPopup(this);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void maybeAutoSwitchOnColdBoot() {
        if (coldBootAutoSwitchAttempted) return;
        coldBootAutoSwitchAttempted = true;
        // If Android just booted (< 90s uptime) and auto-boot is ON, switch straight to chosen input.
        if (SystemClock.elapsedRealtime() < 90_000L && InputManagerHelper.isAutoBootDpEnabled(this)) {
            final String targetId = InputManagerHelper.getDefaultInputId(this);
            clockHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    InputManagerHelper.switchToInput(MainActivity.this, targetId);
                }
            }, 1500L);
        }
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setClipChildren(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipToPadding(false);
        root.setClipChildren(false);
        root.setPadding(dp(48), dp(26), dp(48), dp(52));

        // --- TOP HEADER BAR ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipToPadding(false);
        header.setClipChildren(false);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(20);
        root.addView(header, headerLp);

        // Left: Title + Monitor label
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("KTC A32Q8");
        brand.setTextColor(Color.parseColor("#E2E8F0"));
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleCol.addView(brand);

        TextView sub = new TextView(this);
        sub.setText("Open Smart Monitor");
        sub.setTextColor(Color.parseColor("#7E8CA6"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleCol.addView(sub);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Right controls: Boot target cycle pill (DP -> USB-C -> HDMI 1 -> OFF), Android Settings, Clock
        autoBootButton = createPillButton("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String next = InputManagerHelper.cycleBootTarget(MainActivity.this);
                updateAutoBootPill();
                refreshInputsRow();
                Toast.makeText(
                        MainActivity.this,
                        "OFF".equals(next) ? "Auto-boot input: OFF" : "Auto-boot input: " + next,
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
        autoBootButton.setId(View.generateViewId());
        updateAutoBootPill();
        header.addView(autoBootButton, pillLp(dp(12)));

        settingsButton = createPillButton("Settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "Cannot open Settings", Toast.LENGTH_SHORT).show();
                }
            }
        });
        settingsButton.setId(View.generateViewId());
        header.addView(settingsButton, pillLp(dp(22)));

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END);

        clockView = new TextView(this);
        clockView.setTextColor(Color.parseColor("#F1F5F9"));
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockCol.addView(clockView);

        dateView = new TextView(this);
        dateView.setTextColor(Color.parseColor("#8A99B5"));
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clockCol.addView(dateView);

        header.addView(clockCol);

        // --- INPUTS SECTION ---
        root.addView(createSectionHeader("INPUTS"), sectionLp());

        inputsRow = new LinearLayout(this);
        inputsRow.setOrientation(LinearLayout.HORIZONTAL);
        inputsRow.setClipToPadding(false);
        inputsRow.setClipChildren(false);
        inputsRow.setPadding(0, dp(4), 0, dp(8));

        LinearLayout.LayoutParams inputsRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputsRowLp.bottomMargin = dp(18);
        root.addView(inputsRow, inputsRowLp);

        // --- APPS SECTION ---
        root.addView(createSectionHeader("APPS"), sectionLp());

        appsGridContainer = new LinearLayout(this);
        appsGridContainer.setOrientation(LinearLayout.VERTICAL);
        appsGridContainer.setClipToPadding(false);
        appsGridContainer.setClipChildren(false);
        appsGridContainer.setPadding(0, dp(4), 0, dp(16));
        root.addView(appsGridContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView.addView(root);
        setContentView(scrollView);
    }

    private void updateAutoBootPill() {
        String target = InputManagerHelper.getBootTargetShortName(this);
        if ("OFF".equals(target)) {
            autoBootButton.setText("Boot: OFF");
            autoBootButton.setTextColor(Color.parseColor("#94A3B8"));
        } else {
            autoBootButton.setText("Boot -> " + target);
            autoBootButton.setTextColor(Color.parseColor("#9ECE6A"));
        }
    }

    private void updateClock() {
        Date now = new Date();
        if (clockView != null) clockView.setText(timeFormat.format(now));
        if (dateView != null) dateView.setText(dateFormat.format(now));
    }

    private void refreshInputsRow() {
        inputsRow.removeAllViews();
        firstInputCard = null;

        List<InputManagerHelper.InputItem> inputs = InputManagerHelper.loadHardwareInputs(this);
        for (int i = 0; i < inputs.size(); i++) {
            final InputManagerHelper.InputItem item = inputs.get(i);
            View card = createInputCard(item);
            card.setId(View.generateViewId());
            card.setNextFocusUpId(autoBootButton.getId());
            if (i == 0) {
                firstInputCard = card;
                autoBootButton.setNextFocusDownId(card.getId());
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1f);
            lp.rightMargin = dp(16);
            inputsRow.addView(card, lp);
        }

        // Flush right-aligned "All Inputs" utility card with matching monitor icon
        View popupCard = createUtilityCard("All Inputs", "Monitor OSD", R.drawable.ic_inputs_osd, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputManagerHelper.openNativeInputPopup(MainActivity.this);
            }
        });
        popupCard.setId(View.generateViewId());
        popupCard.setNextFocusUpId(settingsButton.getId());
        settingsButton.setNextFocusDownId(popupCard.getId());
        LinearLayout.LayoutParams popupLp = new LinearLayout.LayoutParams(0, dp(96), 0.85f);
        inputsRow.addView(popupCard, popupLp);

        if (firstInputCard != null && getCurrentFocus() == null) {
            firstInputCard.requestFocus();
        }
    }

    private View createInputCard(final InputManagerHelper.InputItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(18), dp(12), dp(16), dp(12));
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setClickable(true);

        ImageView icon = new ImageView(this);
        icon.setImageResource(item.iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconLp.rightMargin = dp(14);
        card.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(item.label);
        title.setTextColor(Color.parseColor("#F1F5F9"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setSingleLine(true);
        textCol.addView(title);

        TextView sub = new TextView(this);
        boolean isBootDefault = InputManagerHelper.isAutoBootDpEnabled(this)
                && item.inputId.equals(InputManagerHelper.getDefaultInputId(this));
        String subText = isBootDefault ? (item.subtitle + " • Boot") : item.subtitle;
        sub.setText(subText);
        sub.setTextColor(item.active
                ? Color.parseColor("#9ECE6A")
                : (isBootDefault ? Color.parseColor("#7AA2F7") : Color.parseColor("#8A99B5")));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setSingleLine(true);
        textCol.addView(sub);

        card.addView(textCol);

        attachFocusScale(card);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputManagerHelper.switchToInput(MainActivity.this, item.inputId);
            }
        });

        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                InputManagerHelper.setDefaultInputId(MainActivity.this, item.inputId);
                InputManagerHelper.setAutoBootDpEnabled(MainActivity.this, true);
                updateAutoBootPill();
                refreshInputsRow();
                Toast.makeText(MainActivity.this, "Auto-boot input set to " + item.label, Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        return card;
    }

    private View createUtilityCard(String label, String subtitle, int iconRes, View.OnClickListener clickListener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(16), dp(12), dp(14), dp(12));
        card.setFocusable(true);
        card.setClickable(true);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconLp.rightMargin = dp(12);
        card.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(Color.parseColor("#CBD5E1"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setSingleLine(true);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(Color.parseColor("#7E8CA6"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setSingleLine(true);
        textCol.addView(sub);

        card.addView(textCol);

        attachFocusScale(card);
        card.setOnClickListener(clickListener);
        return card;
    }

    private static final class AppEntry {
        final String packageName;
        final String activityName;
        final String label;
        final Drawable bannerOrIcon;
        final boolean isBanner;

        AppEntry(String pkg, String act, String label, Drawable drawable, boolean isBanner) {
            this.packageName = pkg;
            this.activityName = act;
            this.label = label;
            this.bannerOrIcon = drawable;
            this.isBanner = isBanner;
        }
    }

    private void refreshAppsGrid() {
        appsGridContainer.removeAllViews();
        PackageManager pm = getPackageManager();

        Intent tvIntent = new Intent(Intent.ACTION_MAIN, null);
        tvIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(tvIntent, 0);

        Set<String> seenPackages = new HashSet<>();
        // Hide self, Projectivy (if installed), and system Settings (already in header)
        seenPackages.add(getPackageName());
        seenPackages.add("com.spocky.projengmenu");
        seenPackages.add("com.android.tv.settings");

        List<AppEntry> apps = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            String pkg = ri.activityInfo.packageName;
            if (seenPackages.contains(pkg)) continue;
            seenPackages.add(pkg);

            CharSequence cs = ri.loadLabel(pm);
            String label = cs != null ? cs.toString().trim() : pkg;

            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) {
                banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
            boolean hasBanner = (banner != null);
            Drawable drawable = hasBanner ? banner : ri.loadIcon(pm);

            apps.add(new AppEntry(pkg, ri.activityInfo.name, label, drawable, hasBanner));
        }

        Collections.sort(apps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        final int columns = 5;
        LinearLayout currentRow = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % columns == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setClipToPadding(false);
                currentRow.setClipChildren(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(16);
                appsGridContainer.addView(currentRow, rowLp);
            }

            View appCard = createAppCard(apps.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(110), 1f);
            lp.rightMargin = ((i + 1) % columns == 0) ? 0 : dp(16);
            currentRow.addView(appCard, lp);
        }

        // Pad last row with invisible spacers so card widths stay uniform
        int remainder = apps.size() % columns;
        if (remainder != 0 && currentRow != null) {
            for (int j = remainder; j < columns; j++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(110), 1f);
                lp.rightMargin = (j == columns - 1) ? 0 : dp(16);
                currentRow.addView(spacer, lp);
            }
        }
    }

    private View createAppCard(final AppEntry app) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(8), dp(8), dp(8), dp(7));
        card.setFocusable(true);
        card.setClickable(true);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.bannerOrIcon);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            final float cornerRadius = dp(8);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            });
            img.setClipToOutline(true);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(68));
            card.addView(img, imgLp);
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(dp(48), dp(48));
            imgLp.topMargin = dp(8);
            imgLp.bottomMargin = dp(6);
            card.addView(img, imgLp);
        }

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(Color.parseColor("#E2E8F0"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(6);
        card.addView(label, labelLp);

        attachFocusScale(card);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent launch = new Intent(Intent.ACTION_MAIN);
                    launch.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                    launch.setClassName(app.packageName, app.activityName);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                } catch (Throwable t) {
                    Intent fallback = getPackageManager().getLeanbackLaunchIntentForPackage(app.packageName);
                    if (fallback == null) {
                        fallback = getPackageManager().getLaunchIntentForPackage(app.packageName);
                    }
                    if (fallback != null) {
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fallback);
                    } else {
                        Toast.makeText(MainActivity.this, "Unable to open " + app.label, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        return card;
    }

    private TextView createSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#7AA2F7"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setLetterSpacing(0.14f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return tv;
    }

    private LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        return lp;
    }

    private TextView createPillButton(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.parseColor("#CBD5E1"));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setBackgroundResource(R.drawable.pill_bg);
        btn.setPadding(dp(16), dp(8), dp(16), dp(8));
        btn.setFocusable(true);
        btn.setClickable(true);
        attachFocusScale(btn);
        btn.setOnClickListener(listener);
        return btn;
    }

    private LinearLayout.LayoutParams pillLp(int rightMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = rightMarginPx;
        return lp;
    }

    private void attachFocusScale(final View v) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(final View view, boolean hasFocus) {
                float scale = hasFocus ? 1.045f : 1.0f;
                view.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(100)
                        .start();
                view.setElevation(hasFocus ? dp(8) : 0f);
                if (hasFocus) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            android.graphics.Rect rect = new android.graphics.Rect(
                                    -dp(12), -dp(16), view.getWidth() + dp(12), view.getHeight() + dp(20));
                            view.requestRectangleOnScreen(rect, false);
                        }
                    });
                }
            }
        });
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
