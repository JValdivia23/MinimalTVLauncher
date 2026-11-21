package com.ktc.launcher;

import android.graphics.Color;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * Native Android TV System Screensaver (DreamService) displaying the live MRMS weather radar.
 *
 * - Only runs when Android TV itself is idle (never interrupts DisplayPort / USB-C / HDMI PC use)
 * - Immediately wakes and frees 100% of WebView memory on any remote keypress
 */
public class RadarDreamService extends DreamService {

    private WebView webView;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Safety guard: never display Android screensaver over an active PC/DisplayPort session
        if (RadarWebHelper.isHardwarePassthroughActive(this)) {
            finish();
            return;
        }

        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.parseColor("#0D1117"));

        webView = RadarWebHelper.createRadarWebView(this, true);
        container.addView(webView);
        setContentView(container);
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        RadarWebHelper.destroyWebView(webView);
        webView = null;
    }

    @Override
    public void onDetachedFromWindow() {
        RadarWebHelper.destroyWebView(webView);
        webView = null;
        super.onDetachedFromWindow();
        // Kill isolated :radar WebView process on wake so 100% of Chromium RAM is returned immediately
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            wakeUp();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
