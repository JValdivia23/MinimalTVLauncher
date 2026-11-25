package com.ktc.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
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
 * Ultra-lightweight, zero-bloat Obsidian Spatial launcher built for the
 * KTC A32Q8 (Realtek RTD2851F) Smart Monitor.
 *
 * Features:
 * - Zero-blur dynamic dual-tone ambient radial backlight + micro-noise anti-banding texture
 * - Frosted obsidian glass utility capsule (Radar, Sleep Timer, Auto-Boot) + circular Settings button
 * - Machined specular hardware source dock (DisplayPort, USB-C, HDMI 1, recessed All Inputs)
 * - True 16:9 cinema app gallery with rest-state dimming, automatic white-slab obsidian remapping,
 *   bespoke system utility lockups, and tvOS-style focus-reveal floating titles
 */
public class MainActivity extends Activity {

    private static final PathInterpolator FOCUS_EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private static final long FOCUS_ANIM_MS = 195L;

    private static final int ACCENT_DP = 0xFF60A5FA;
    private static final int ACCENT_USBC = 0xFF34D399;
    private static final int ACCENT_HDMI = 0xFFA78BFA;
    private static final int ACCENT_OSD = 0xFF94A3B8;
    private static final int ACCENT_RADAR = 0xFF38BDF8;
    private static final int ACCENT_SLEEP = 0xFFA78BFA;
    private static final int ACCENT_BOOT = 0xFF34D399;
    private static final int ACCENT_SETTINGS = 0xFF94A3B8;
    private static final int ACCENT_MEDIA = 0xFF38BDF8;
    private static final int ACCENT_MIRACAST = 0xFF818CF8;

    private static final ColorMatrixColorFilter REST_TILE_DIM_FILTER = createBrightnessFilter(0.82f);

    private static boolean coldBootAutoSwitchAttempted = false;

    private FrameLayout rootFrame;
    private ScrollView mainScrollView;
    private AmbientBackdropDrawable ambientBackdrop;
    private LinearLayout inputsRow;
    private LinearLayout appsGridContainer;
    private TextView clockView;
    private TextView dateView;

    private LinearLayout radarButton;
    private LinearLayout sleepButton;
    private ImageView sleepIconView;
    private TextView sleepLabelView;
    private LinearLayout autoBootButton;
    private ImageView autoBootIconView;
    private TextView autoBootLabelView;
    private FrameLayout settingsButton;

    private View firstInputCard;
    private final int[] locBuf = new int[2];

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.US);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM d", Locale.US);

    private static ColorMatrixColorFilter createBrightnessFilter(float scale) {
        ColorMatrix cm = new ColorMatrix();
        cm.setScale(scale, scale, scale, 1f);
        return new ColorMatrixColorFilter(cm);
    }

    /**
     * Enforces an exact 16:9 aspect ratio for app tiles across all display densities.
     */
    private static final class AspectRatioFrameLayout extends FrameLayout {
        AspectRatioFrameLayout(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int exactHeight = Math.round(width * 9f / 16f);
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(exactHeight, MeasureSpec.EXACTLY)
            );
        }
    }

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            updateSleepPill();
            long nextDelay = (SleepTimerHelper.isActive(MainActivity.this)
                    && SleepTimerHelper.getRemainingMs(MainActivity.this) <= 65_000L)
                    ? 1000L : 10_000L;
            clockHandler.postDelayed(this, nextDelay);
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
        refreshInputsRow(-1);
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
        updateSleepPill();
        updateAutoBootPill();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
        refreshInputsRow(-1);
        if (firstInputCard != null) {
            firstInputCard.requestFocus();
            firstInputCard.post(new Runnable() {
                @Override
                public void run() {
                    if (getCurrentFocus() == null && firstInputCard != null) {
                        firstInputCard.requestFocus();
                    }
                }
            });
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
        rootFrame = new FrameLayout(this);
        ambientBackdrop = new AmbientBackdropDrawable();
        ambientBackdrop.setImmediate(ACCENT_DP, 0.20f, 0.28f);
        rootFrame.setBackground(ambientBackdrop);
        rootFrame.setClipChildren(false);
        rootFrame.setClipToPadding(false);

        mainScrollView = new ScrollView(this) {
            @Override
            protected float getTopFadingEdgeStrength() {
                return getScrollY() <= dp(6) ? 0f : super.getTopFadingEdgeStrength();
            }

            @Override
            protected float getBottomFadingEdgeStrength() {
                View child = getChildCount() > 0 ? getChildAt(0) : null;
                if (child == null) return 0f;
                int remaining = child.getBottom() - (getScrollY() + getHeight());
                if (remaining <= dp(8)) return 0f;
                // Keep bottom fade gentle at rest so Row 2 tiles stay 100% crisp
                if (getScrollY() <= dp(6)) return 0.35f;
                return super.getBottomFadingEdgeStrength();
            }
        };
        mainScrollView.setFillViewport(true);
        mainScrollView.setVerticalScrollBarEnabled(false);
        mainScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mainScrollView.setVerticalFadingEdgeEnabled(true);
        mainScrollView.setFadingEdgeLength(dp(34));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipToPadding(false);
        root.setClipChildren(false);
        root.setPadding(dp(48), dp(32), dp(48), dp(44));

        // --- QUIET SPATIAL HEADER BAR ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipToPadding(false);
        header.setClipChildren(false);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(28);
        root.addView(header, headerLp);

        // Left: Refined hardware identity
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView brand = new TextView(this);
        brand.setText("KTC A32Q8");
        brand.setTextColor(0xF5F8FAFC);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        brand.setLetterSpacing(0.015f);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleCol.addView(brand);

        TextView sub = new TextView(this);
        sub.setText("Open Smart Monitor");
        sub.setTextColor(0x8894A3B8);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setLetterSpacing(0.025f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(1);
        titleCol.addView(sub, subLp);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Center-Right: 3-chip frosted obsidian utility capsule + standalone circular Settings button
        LinearLayout controlCapsule = new LinearLayout(this);
        controlCapsule.setOrientation(LinearLayout.HORIZONTAL);
        controlCapsule.setGravity(Gravity.CENTER_VERTICAL);
        controlCapsule.setBackgroundResource(R.drawable.glass_capsule_bg);
        controlCapsule.setPadding(dp(4), dp(4), dp(4), dp(4));
        controlCapsule.setClipChildren(false);
        controlCapsule.setClipToPadding(false);

        radarButton = createRadarChip();
        radarButton.setId(View.generateViewId());
        controlCapsule.addView(radarButton);

        controlCapsule.addView(createCapsuleDivider());

        sleepButton = createSleepChip();
        sleepButton.setId(View.generateViewId());
        updateSleepPill();
        controlCapsule.addView(sleepButton);

        controlCapsule.addView(createCapsuleDivider());

        autoBootButton = createAutoBootChip();
        autoBootButton.setId(View.generateViewId());
        updateAutoBootPill();
        controlCapsule.addView(autoBootButton);

        LinearLayout.LayoutParams capsuleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capsuleLp.rightMargin = dp(10);
        header.addView(controlCapsule, capsuleLp);

        settingsButton = createSettingsButton();
        settingsButton.setId(View.generateViewId());
        autoBootButton.setNextFocusRightId(settingsButton.getId());
        settingsButton.setNextFocusLeftId(autoBootButton.getId());

        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        settingsLp.rightMargin = dp(26);
        header.addView(settingsButton, settingsLp);

        // Far Right: Editorial tabular clock
        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END);

        clockView = new TextView(this);
        clockView.setTextColor(0xFFF8FAFC);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 29);
        clockView.setLetterSpacing(-0.02f);
        clockView.setFontFeatureSettings("tnum");
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockCol.addView(clockView);

        dateView = new TextView(this);
        dateView.setTextColor(0x8894A3B8);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        dateView.setLetterSpacing(0.02f);
        clockCol.addView(dateView);

        header.addView(clockCol);

        // --- SOURCES SECTION ---
        root.addView(createSectionHeader("Sources"), sectionLp(dp(11)));

        inputsRow = new LinearLayout(this);
        inputsRow.setOrientation(LinearLayout.HORIZONTAL);
        inputsRow.setClipToPadding(false);
        inputsRow.setClipChildren(false);
        inputsRow.setPadding(0, dp(2), 0, dp(4));

        LinearLayout.LayoutParams inputsRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputsRowLp.bottomMargin = dp(30);
        root.addView(inputsRow, inputsRowLp);

        // --- APPS SECTION ---
        root.addView(createSectionHeader("Apps"), sectionLp(dp(13)));

        appsGridContainer = new LinearLayout(this);
        appsGridContainer.setOrientation(LinearLayout.VERTICAL);
        appsGridContainer.setClipToPadding(false);
        appsGridContainer.setClipChildren(false);
        appsGridContainer.setPadding(0, dp(2), 0, dp(16));
        root.addView(appsGridContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mainScrollView.addView(root);
        rootFrame.addView(mainScrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootFrame);
    }

    private View createCapsuleDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(0x16FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(13));
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        divider.setLayoutParams(lp);
        return divider;
    }

    private LinearLayout createBaseGlassChip(int padStartDp, int padEndDp) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.glass_chip_bg);
        chip.setPadding(dp(padStartDp), dp(6), dp(padEndDp), dp(6));
        chip.setFocusable(true);
        chip.setClickable(true);
        applyRoundedOutline(chip, dp(18), false);
        return chip;
    }

    private LinearLayout createRadarChip() {
        LinearLayout chip = createBaseGlassChip(13, 14);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_radar);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(15), dp(15));
        iconLp.rightMargin = dp(7);
        chip.addView(icon, iconLp);

        TextView label = new TextView(this);
        label.setText("Radar");
        label.setTextColor(0xDCFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.addView(label);

        attachChipFocusEffect(chip, ACCENT_RADAR);

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, RadarActivity.class);
                startActivity(i);
            }
        });
        return chip;
    }

    private LinearLayout createSleepChip() {
        LinearLayout chip = createBaseGlassChip(13, 14);

        sleepIconView = new ImageView(this);
        sleepIconView.setImageResource(R.drawable.ic_sleep);
        sleepIconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        iconLp.rightMargin = dp(6);
        chip.addView(sleepIconView, iconLp);

        sleepLabelView = new TextView(this);
        sleepLabelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        sleepLabelView.setFontFeatureSettings("tnum");
        sleepLabelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.addView(sleepLabelView);

        attachChipFocusEffect(chip, ACCENT_SLEEP);

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int mins = SleepTimerHelper.cyclePreset(MainActivity.this);
                updateSleepPill();
                clockHandler.removeCallbacks(clockTick);
                clockHandler.post(clockTick);
                Toast.makeText(
                        MainActivity.this,
                        mins == 0 ? "Sleep timer: OFF" : "Sleep timer: " + mins + " min",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        chip.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (SleepTimerHelper.isActive(MainActivity.this)) {
                    SleepTimerHelper.cancel(MainActivity.this);
                    updateSleepPill();
                    Toast.makeText(MainActivity.this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Sleep timer is already OFF", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        return chip;
    }

    private LinearLayout createAutoBootChip() {
        LinearLayout chip = createBaseGlassChip(13, 14);

        autoBootIconView = new ImageView(this);
        autoBootIconView.setImageResource(R.drawable.ic_boot);
        autoBootIconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        iconLp.rightMargin = dp(6);
        chip.addView(autoBootIconView, iconLp);

        autoBootLabelView = new TextView(this);
        autoBootLabelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        autoBootLabelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.addView(autoBootLabelView);

        attachChipFocusEffect(chip, ACCENT_BOOT);

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String next = InputManagerHelper.cycleBootTarget(MainActivity.this);
                updateAutoBootPill();
                refreshInputsRow(-1);
                Toast.makeText(
                        MainActivity.this,
                        "OFF".equals(next) ? "Auto-boot input: OFF" : "Auto-boot input: " + next,
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
        return chip;
    }

    private FrameLayout createSettingsButton() {
        FrameLayout btn = new FrameLayout(this);
        btn.setBackgroundResource(R.drawable.circle_glass_btn_bg);
        btn.setContentDescription("Settings");
        btn.setFocusable(true);
        btn.setClickable(true);
        applyRoundedOutline(btn, dp(19), false);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_settings);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(0xD8FFFFFF, PorterDuff.Mode.SRC_IN);
        btn.addView(icon, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));

        attachChipFocusEffect(btn, ACCENT_SETTINGS);

        btn.setOnClickListener(new View.OnClickListener() {
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
        return btn;
    }

    private void updateSleepPill() {
        if (sleepLabelView == null) return;
        boolean active = SleepTimerHelper.isActive(this);
        sleepLabelView.setText(SleepTimerHelper.formatPillLabel(this));
        sleepLabelView.setTextColor(active ? 0xFFDDD6FE : 0xDCFFFFFF);
        if (sleepIconView != null) {
            sleepIconView.setColorFilter(active ? 0xFFC4B5FD : 0xB8FFFFFF, PorterDuff.Mode.SRC_IN);
        }
    }

    private void updateAutoBootPill() {
        if (autoBootLabelView == null) return;
        String target = InputManagerHelper.getBootTargetShortName(this);
        boolean off = "OFF".equals(target);
        if (off) {
            autoBootLabelView.setText("Boot · Off");
            autoBootLabelView.setTextColor(0x78FFFFFF);
            if (autoBootIconView != null) {
                autoBootIconView.setColorFilter(0x70FFFFFF, PorterDuff.Mode.SRC_IN);
            }
        } else {
            // Calm unified label; emerald reserved strictly for icon & live signal LED
            autoBootLabelView.setText("Boot · " + target);
            autoBootLabelView.setTextColor(0xDCFFFFFF);
            if (autoBootIconView != null) {
                autoBootIconView.setColorFilter(0xFF34D399, PorterDuff.Mode.SRC_IN);
            }
        }
    }

    private void updateClock() {
        Date now = new Date();
        if (clockView != null) {
            String raw = timeFormat.format(now);
            int spaceIdx = raw.lastIndexOf(' ');
            if (spaceIdx > 0 && spaceIdx < raw.length() - 1) {
                SpannableString ss = new SpannableString(raw);
                ss.setSpan(new RelativeSizeSpan(0.46f), spaceIdx + 1, raw.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new ForegroundColorSpan(0x8C94A3B8), spaceIdx + 1, raw.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                clockView.setText(ss);
            } else {
                clockView.setText(raw);
            }
        }
        if (dateView != null) {
            dateView.setText(dateFormat.format(now));
        }
    }

    private void refreshInputsRow(int restoreFocusIdx) {
        inputsRow.removeAllViews();
        firstInputCard = null;
        View restoreTarget = null;

        List<InputManagerHelper.InputItem> inputs = InputManagerHelper.loadHardwareInputs(this);
        for (int i = 0; i < inputs.size(); i++) {
            final int index = i;
            final InputManagerHelper.InputItem item = inputs.get(i);
            View card = createInputCard(item, index);
            card.setId(View.generateViewId());

            if (i == 0) {
                firstInputCard = card;
                card.setNextFocusUpId(radarButton.getId());
                radarButton.setNextFocusDownId(card.getId());
            } else if (i == 1) {
                card.setNextFocusUpId(sleepButton.getId());
                sleepButton.setNextFocusDownId(card.getId());
            } else {
                card.setNextFocusUpId(autoBootButton.getId());
                autoBootButton.setNextFocusDownId(card.getId());
            }

            if (i == restoreFocusIdx) {
                restoreTarget = card;
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(72), 1f);
            lp.rightMargin = dp(14);
            inputsRow.addView(card, lp);
        }

        View popupCard = createUtilityCard("All Inputs", "Monitor OSD", R.drawable.ic_inputs_osd, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputManagerHelper.openNativeInputPopup(MainActivity.this);
            }
        });
        popupCard.setId(View.generateViewId());
        popupCard.setNextFocusUpId(settingsButton.getId());
        settingsButton.setNextFocusDownId(popupCard.getId());
        LinearLayout.LayoutParams popupLp = new LinearLayout.LayoutParams(0, dp(72), 0.78f);
        inputsRow.addView(popupCard, popupLp);

        if (restoreTarget != null) {
            restoreTarget.requestFocus();
        } else if (firstInputCard != null && getCurrentFocus() == null) {
            firstInputCard.requestFocus();
        }
    }

    private int accentForInput(InputManagerHelper.InputItem item) {
        if (item.iconRes == R.drawable.ic_dp) return ACCENT_DP;
        if (item.iconRes == R.drawable.ic_usbc) return ACCENT_USBC;
        if (item.iconRes == R.drawable.ic_hdmi) return ACCENT_HDMI;
        return ACCENT_OSD;
    }

    private String cleanSpecSubtitle(String rawSubtitle) {
        if (rawSubtitle == null) return "";
        return rawSubtitle.replace(" • Active", "").replace("• Active", "").trim();
    }

    private Drawable createPortWashOverlay(int accentColor, int leftAlpha) {
        int start = Color.argb(leftAlpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { start, 0x00000000 }
        );
        gd.setCornerRadius(dp(15));
        return gd;
    }

    private View createInputCard(final InputManagerHelper.InputItem item, final int cardIndex) {
        final int accent = accentForInput(item);

        FrameLayout card = new FrameLayout(this);
        card.setBackgroundResource(R.drawable.source_tile_bg);
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setClickable(true);
        applyRoundedOutline(card, dp(16), true);

        // Subtle internal port-accent wash bleeding gently rightward from the connector well
        final View portWash = new View(this);
        portWash.setBackground(createPortWashOverlay(accent, item.active ? 0x12 : 0x00));
        FrameLayout.LayoutParams washLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        washLp.setMargins(dp(1), dp(1), dp(1), dp(1));
        card.addView(portWash, washLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setDuplicateParentStateEnabled(true);
        content.setPadding(dp(13), dp(10), dp(13), dp(10));
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Carved borderless obsidian icon well
        FrameLayout iconWell = new FrameLayout(this);
        iconWell.setDuplicateParentStateEnabled(true);
        iconWell.setBackgroundResource(R.drawable.icon_well_bg);
        ImageView icon = new ImageView(this);
        icon.setImageResource(item.iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconWell.addView(icon, new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));

        LinearLayout.LayoutParams wellLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        wellLp.rightMargin = dp(11);
        content.addView(iconWell, wellLp);

        // Center text stack
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(item.label);
        title.setTextColor(0xFFF4F7FB);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(title);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusRowLp.topMargin = dp(2);

        String spec = cleanSpecSubtitle(item.subtitle);
        if (item.active) {
            View led = new View(this);
            led.setBackgroundResource(R.drawable.dot_active);
            LinearLayout.LayoutParams ledLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            ledLp.rightMargin = dp(5);
            statusRow.addView(led, ledLp);
        }

        TextView sub = new TextView(this);
        sub.setText(item.active ? (spec.isEmpty() ? "Active" : spec + " · Active") : spec);
        sub.setTextColor(item.active ? 0xFF86EFAC : 0x8094A3B8);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        statusRow.addView(sub);

        textCol.addView(statusRow, statusRowLp);
        content.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Recessed stroke-free BOOT micro-tag
        boolean isBootDefault = InputManagerHelper.isAutoBootDpEnabled(this)
                && item.inputId.equals(InputManagerHelper.getDefaultInputId(this));
        if (isBootDefault) {
            TextView bootBadge = new TextView(this);
            bootBadge.setText("BOOT");
            bootBadge.setTextColor(0xB8CBD5E1);
            bootBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
            bootBadge.setLetterSpacing(0.12f);
            bootBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            bootBadge.setBackgroundResource(R.drawable.badge_boot_bg);
            bootBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.leftMargin = dp(6);
            content.addView(bootBadge, badgeLp);
        }

        attachSourceFocusEffect(card, portWash, accent, item.active);

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
                refreshInputsRow(cardIndex);
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
        card.setBackgroundResource(R.drawable.utility_tile_bg);
        card.setPadding(dp(13), dp(10), dp(13), dp(10));
        card.setFocusable(true);
        card.setClickable(true);
        applyRoundedOutline(card, dp(16), false);

        FrameLayout iconWell = new FrameLayout(this);
        iconWell.setDuplicateParentStateEnabled(true);
        iconWell.setBackgroundResource(R.drawable.icon_well_bg);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setAlpha(0.85f);
        iconWell.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));

        LinearLayout.LayoutParams wellLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        wellLp.rightMargin = dp(10);
        card.addView(iconWell, wellLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(0xD4F1F5F9);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(0x7094A3B8);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        textCol.addView(sub, subLp);

        card.addView(textCol);

        attachSourceFocusEffect(card, null, ACCENT_OSD, false);
        card.setOnClickListener(clickListener);
        return card;
    }

    private static final class AppEntry {
        final String packageName;
        final String activityName;
        final String label;
        final Drawable bannerOrIcon;
        final boolean isBanner;
        final int customLockupIconRes;
        final int accentColor;

        AppEntry(
                String pkg,
                String act,
                String label,
                Drawable drawable,
                boolean isBanner,
                int customLockupIconRes,
                int accentColor
        ) {
            this.packageName = pkg;
            this.activityName = act;
            this.label = label;
            this.bannerOrIcon = drawable;
            this.isBanner = isBanner;
            this.customLockupIconRes = customLockupIconRes;
            this.accentColor = accentColor;
        }
    }

    private static int bespokeUtilityIconForPackage(String pkg) {
        if ("com.rtk.mediabrowser".equals(pkg)) return R.drawable.ic_mediaplayer;
        if ("com.realtek.miracast".equals(pkg)) return R.drawable.ic_miracast;
        return 0;
    }

    private static int bespokeUtilityAccentForPackage(String pkg) {
        if ("com.rtk.mediabrowser".equals(pkg)) return ACCENT_MEDIA;
        if ("com.realtek.miracast".equals(pkg)) return ACCENT_MIRACAST;
        return AmbientBackdropDrawable.DEFAULT_ACCENT;
    }

    private void refreshAppsGrid() {
        appsGridContainer.removeAllViews();
        PackageManager pm = getPackageManager();

        Intent tvIntent = new Intent(Intent.ACTION_MAIN, null);
        tvIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(tvIntent, 0);

        Set<String> seenPackages = new HashSet<>();
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

            int customIconRes = bespokeUtilityIconForPackage(pkg);
            if (customIconRes != 0) {
                int accent = bespokeUtilityAccentForPackage(pkg);
                apps.add(new AppEntry(pkg, ri.activityInfo.name, label, null, true, customIconRes, accent));
                continue;
            }

            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) {
                banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
            boolean hasBanner = (banner != null);
            Drawable rawDrawable = hasBanner ? banner : ri.loadIcon(pm);
            int accent = AmbientBackdropDrawable.extractAccentColor(rawDrawable, AmbientBackdropDrawable.DEFAULT_ACCENT);
            Drawable finalDrawable = hasBanner
                    ? AmbientBackdropDrawable.remapLightBannerIfNeeded(rawDrawable, accent, getResources())
                    : rawDrawable;

            apps.add(new AppEntry(pkg, ri.activityInfo.name, label, finalDrawable, hasBanner, 0, accent));
        }

        Collections.sort(apps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        final int columns = 5;
        LinearLayout currentRow = null;
        int totalRows = (apps.size() + columns - 1) / columns;
        for (int i = 0; i < apps.size(); i++) {
            int rowIndex = i / columns;
            if (i % columns == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setClipToPadding(false);
                currentRow.setClipChildren(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                // Give Row 2 extra bottom margin so Row 3 sits cleanly below the 1080p home fold
                rowLp.bottomMargin = (rowIndex == 1 && totalRows > 2) ? dp(28) : dp(13);
                appsGridContainer.addView(currentRow, rowLp);
            }

            View appTile = createAppTile(apps.get(i), currentRow, rowIndex);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = ((i + 1) % columns == 0) ? 0 : dp(18);
            currentRow.addView(appTile, lp);
        }

        // Pad last row with invisible 16:9 spacers so tile widths stay uniform
        int remainder = apps.size() % columns;
        if (remainder != 0 && currentRow != null) {
            for (int j = remainder; j < columns; j++) {
                View spacer = new AspectRatioFrameLayout(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = (j == columns - 1) ? 0 : dp(18);
                currentRow.addView(spacer, lp);
            }
        }
    }

    /**
     * Builds a true 16:9 spatial app tile:
     * - Unfocused tiles rest slightly dimmed (0.86x) so only the focused tile blooms to 100% brilliance
     * - Banner tiles hide redundant text labels at rest and reveal them smoothly on focus (tvOS style)
     */
    private View createAppTile(final AppEntry app, final View parentRow, final int rowIndex) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setFocusable(true);
        container.setClickable(true);
        container.setClipChildren(false);
        container.setClipToPadding(false);

        final AspectRatioFrameLayout tileBox = new AspectRatioFrameLayout(this);
        tileBox.setDuplicateParentStateEnabled(true);
        tileBox.setBackgroundColor(0xFF0E1118);
        applyRoundedOutline(tileBox, dp(13), true);
        tileBox.setForeground(getDrawable(R.drawable.app_focus_ring));

        ImageView dimmableArt = null;
        View dimmableLockup = null;

        if (app.customLockupIconRes != 0) {
            View tintedBg = new View(this);
            tintedBg.setBackground(AmbientBackdropDrawable.createIconTileBackdrop(app.accentColor, dp(115)));
            tileBox.addView(tintedBg, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout lockup = new LinearLayout(this);
            lockup.setOrientation(LinearLayout.HORIZONTAL);
            lockup.setGravity(Gravity.CENTER);
            lockup.setAlpha(0.88f);

            ImageView glyph = new ImageView(this);
            glyph.setImageResource(app.customLockupIconRes);
            glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(dp(26), dp(26));
            glyphLp.rightMargin = dp(9);
            lockup.addView(glyph, glyphLp);

            TextView lockupText = new TextView(this);
            lockupText.setText(app.label);
            lockupText.setTextColor(0xFFF1F5F9);
            lockupText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            lockupText.setLetterSpacing(0.01f);
            lockupText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            lockupText.setSingleLine(true);
            lockup.addView(lockupText);

            tileBox.addView(lockup, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            dimmableLockup = lockup;
        } else if (app.isBanner) {
            ImageView img = new ImageView(this);
            img.setImageDrawable(app.bannerOrIcon);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setColorFilter(REST_TILE_DIM_FILTER);
            tileBox.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dimmableArt = img;
        } else {
            View tintedBg = new View(this);
            tintedBg.setBackground(AmbientBackdropDrawable.createIconTileBackdrop(app.accentColor, dp(110)));
            tileBox.addView(tintedBg, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.bannerOrIcon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setColorFilter(REST_TILE_DIM_FILTER);
            tileBox.addView(icon, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
            dimmableArt = icon;
        }

        container.addView(tileBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final boolean hideLabelAtRest = app.isBanner;
        final TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(0xFFF1F5F9);
        label.setAlpha(hideLabelAtRest ? 0f : 0.56f);
        label.setTranslationY(hideLabelAtRest ? -dp(2) : 0f);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        label.setLetterSpacing(0.01f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(5);
        container.addView(label, labelLp);

        attachAppFocusEffect(
                container,
                tileBox,
                dimmableArt,
                dimmableLockup,
                label,
                hideLabelAtRest,
                parentRow,
                rowIndex,
                app.accentColor
        );

        container.setOnClickListener(new View.OnClickListener() {
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

        return container;
    }

    private TextView createSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x7894A3B8);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setLetterSpacing(0.04f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    private LinearLayout.LayoutParams sectionLp(int bottomMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMarginPx;
        return lp;
    }

    private void applyRoundedOutline(View view, final float radiusPx, boolean clip) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        view.setClipToOutline(clip);
    }

    private void attachChipFocusEffect(final View chip, final int accentColor) {
        chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float scale = hasFocus ? 1.05f : 1.0f;
                v.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(FOCUS_ANIM_MS)
                        .setInterpolator(FOCUS_EASE)
                        .start();
                v.setElevation(hasFocus ? dp(10) : 0f);
                v.setTranslationZ(hasFocus ? dp(4) : 0f);
                if (hasFocus) {
                    v.setOutlineSpotShadowColor(accentColor);
                    v.setOutlineAmbientShadowColor(accentColor);
                    updateAmbientForView(v, accentColor);
                    scrollHomeToTop();
                }
            }
        });
    }

    private void attachSourceFocusEffect(
            final View card,
            final View portWash,
            final int accentColor,
            final boolean isActivePort
    ) {
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(final View v, boolean hasFocus) {
                float scale = hasFocus ? 1.038f : 1.0f;
                v.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(FOCUS_ANIM_MS)
                        .setInterpolator(FOCUS_EASE)
                        .start();
                v.setElevation(hasFocus ? dp(16) : 0f);
                v.setTranslationZ(hasFocus ? dp(6) : 0f);
                if (portWash != null) {
                    portWash.setBackground(createPortWashOverlay(
                            accentColor,
                            hasFocus ? 0x24 : (isActivePort ? 0x12 : 0x00)
                    ));
                }
                if (hasFocus) {
                    v.setOutlineSpotShadowColor(accentColor);
                    v.setOutlineAmbientShadowColor(accentColor);
                    updateAmbientForView(v, accentColor);
                    scrollHomeToTop();
                }
            }
        });
    }

    private void attachAppFocusEffect(
            final View container,
            final View tileBox,
            final ImageView dimmableArt,
            final View dimmableLockup,
            final TextView label,
            final boolean hideLabelAtRest,
            final View parentRow,
            final int rowIndex,
            final int accentColor
    ) {
        container.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(final View v, boolean hasFocus) {
                float scale = hasFocus ? 1.072f : 1.0f;
                tileBox.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(FOCUS_ANIM_MS)
                        .setInterpolator(FOCUS_EASE)
                        .start();
                tileBox.setElevation(hasFocus ? dp(18) : 0f);
                container.setTranslationZ(hasFocus ? dp(8) : 0f);
                if (parentRow != null) {
                    parentRow.setTranslationZ(hasFocus ? dp(6) : 0f);
                }

                if (dimmableArt != null) {
                    dimmableArt.setColorFilter(hasFocus ? null : REST_TILE_DIM_FILTER);
                }
                if (dimmableLockup != null) {
                    dimmableLockup.animate()
                            .alpha(hasFocus ? 1.0f : 0.86f)
                            .setDuration(FOCUS_ANIM_MS)
                            .start();
                }

                label.animate()
                        .alpha(hasFocus ? 0.96f : (hideLabelAtRest ? 0f : 0.56f))
                        .translationY(hasFocus ? dp(2) : (hideLabelAtRest ? -dp(2) : 0f))
                        .setDuration(FOCUS_ANIM_MS)
                        .setInterpolator(FOCUS_EASE)
                        .start();

                if (hasFocus) {
                    tileBox.setOutlineSpotShadowColor(accentColor);
                    tileBox.setOutlineAmbientShadowColor(accentColor);
                    updateAmbientForView(tileBox, accentColor);
                    if (rowIndex <= 1) {
                        scrollHomeToTop();
                    } else {
                        ensureVisibleOnScreen(v);
                    }
                }
            }
        });
    }

    private void scrollHomeToTop() {
        if (mainScrollView != null && mainScrollView.getScrollY() != 0) {
            mainScrollView.smoothScrollTo(0, 0);
        }
    }

    private void updateAmbientForView(final View v, final int accentColor) {
        if (ambientBackdrop == null || rootFrame == null) return;
        v.post(new Runnable() {
            @Override
            public void run() {
                int rw = rootFrame.getWidth();
                int rh = rootFrame.getHeight();
                if (rw <= 0 || rh <= 0) {
                    ambientBackdrop.transitionTo(accentColor, 0.50f, 0.30f);
                    return;
                }
                v.getLocationInWindow(locBuf);
                float cx = (locBuf[0] + v.getWidth() * 0.5f) / (float) rw;
                float cy = (locBuf[1] + v.getHeight() * 0.5f) / (float) rh;
                ambientBackdrop.transitionTo(accentColor, cx, cy);
            }
        });
    }

    private void ensureVisibleOnScreen(final View v) {
        v.post(new Runnable() {
            @Override
            public void run() {
                Rect rect = new Rect(-dp(16), -dp(24), v.getWidth() + dp(16), v.getHeight() + dp(28));
                v.requestRectangleOnScreen(rect, false);
            }
        });
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
