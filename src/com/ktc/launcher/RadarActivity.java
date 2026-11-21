package com.ktc.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * On-demand fullscreen MRMS Radar viewer launched from the top-bar "Radar" pill.
 *
 * Remote controls:
 * - OK / Center / Play-Pause : Toggle live radar loop play/pause
 * - D-Pad Up / Down          : Zoom in / out
 * - D-Pad Left / Right       : Step radar scan backward / forward
 * - Back                     : Close and free WebView RAM immediately
 */
public class RadarActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.parseColor("#0D1117"));

        webView = RadarWebHelper.createRadarWebView(this, false);
        container.addView(webView);
        setContentView(container);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (webView != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    webView.evaluateJavascript("if(typeof toggleLivePause==='function')toggleLivePause();", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    webView.evaluateJavascript("if(typeof changeZoom==='function')changeZoom(1);", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    webView.evaluateJavascript("if(typeof changeZoom==='function')changeZoom(-1);", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    webView.evaluateJavascript("if(typeof stepTime==='function')stepTime(1);", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    webView.evaluateJavascript("if(typeof stepTime==='function')stepTime(-1);", null);
                    return true;
                case KeyEvent.KEYCODE_TV_INPUT:
                    InputManagerHelper.openNativeInputPopup(this);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        RadarWebHelper.destroyWebView(webView);
        webView = null;
        super.onDestroy();
        // Kill isolated :radar WebView process on exit so 100% of Chromium RAM is returned immediately
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
