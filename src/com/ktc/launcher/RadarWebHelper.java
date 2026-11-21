package com.ktc.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Shared hardware-accelerated WebView builder for the MRMS Weather Radar viewer
 * (https://jvaldivia23.github.io/personal_webpage/radar/).
 *
 * Designed for zero-bloat performance on 1.8 GB RAM Android TV hardware:
 * - Zero memory overhead until opened; completely destroyed on exit
 * - Hardware GPU layer acceleration + standard HTTP caching
 * - Auto-hides desktop corner buttons and starts the live 18-frame radar loop
 * - Double-checks Realtek DP/HDMI passthrough state so screensaver never interrupts PC use
 */
public final class RadarWebHelper {

    public static final String RADAR_URL = "https://jvaldivia23.github.io/personal_webpage/radar/";

    private RadarWebHelper() {}

    /**
     * Returns true if the monitor is currently displaying a hardware passthrough input
     * (DisplayPort, USB-C, or HDMI) via Realtek Live TV.
     */
    public static boolean isHardwarePassthroughActive(Context ctx) {
        try {
            int liveStarted = Settings.Global.getInt(
                    ctx.getContentResolver(), "is_live_tv_main_activity_started", 0);
            if (liveStarted == 1) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static WebView createRadarWebView(Context ctx, final boolean ambientScreensaverMode) {
        final WebView webView = new WebView(ctx);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(Color.parseColor("#0D1117"));
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setGeolocationEnabled(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                // Keep default Colorado coordinates fast without blocking on TV geolocation prompt
                callback.invoke(origin, false, false);
            }
        });

        final Handler handler = new Handler(Looper.getMainLooper());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(final WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject clean TV styling (hide mouse-only corner buttons & scrollbars)
                String styleJs =
                        "(function(){"
                        + "var st=document.getElementById('ktc-tv-css');"
                        + "if(!st){st=document.createElement('style');st.id='ktc-tv-css';"
                        + "st.textContent='.corner-btn{display:none!important;}body,html{overflow:hidden!important;background:#0d1117!important;cursor:none!important;}';"
                        + "document.head.appendChild(st);}"
                        + "})();";
                view.evaluateJavascript(styleJs, null);

                // Auto-start the live MRMS radar animation loop once initial map settles
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String startLoopJs =
                                    "(function(){"
                                    + "if(typeof S!=='undefined'&&S.mode!=='playing'&&typeof toggleLivePause==='function'){"
                                    + "toggleLivePause();"
                                    + "}"
                                    + "})();";
                            view.evaluateJavascript(startLoopJs, null);
                        } catch (Throwable ignored) {
                        }
                    }
                }, ambientScreensaverMode ? 1800L : 1400L);
            }
        });

        webView.loadUrl(RADAR_URL);
        return webView;
    }

    public static void destroyWebView(WebView webView) {
        if (webView == null) return;
        try {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.onPause();
            webView.removeAllViews();
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.destroy();
        } catch (Throwable ignored) {
        }
    }
}
