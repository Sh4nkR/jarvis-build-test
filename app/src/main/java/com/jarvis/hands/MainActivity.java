package com.jarvis.hands;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.net.http.SslError;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;

/** The screen: a full view of Jarvis's own interface (his face + all the buttons), served by the
 *  PC. A small gear in the corner switches Jarvis on/off, opens Android's Accessibility settings
 *  to give him hands, and sets the PC's address. */
public class MainActivity extends Activity {
    private WebView web;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);          // his voice starts on its own
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError e) {
                handler.proceed();   // Jarvis's own PC, self-signed cert on the home Wi-Fi
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> req.grant(req.getResources()));   // the page gets the mic/camera we already hold
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        Button gear = new Button(this);
        gear.setText("⚙");
        gear.setAlpha(0.5f);
        gear.setOnClickListener(v -> menu());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.TOP | Gravity.END;
        root.addView(gear, lp);

        setContentView(root);
        askPermissions();
        if (Link.prefs(this).getString("host", null) == null) setHost(true);
        else load();
    }

    private void load() {
        web.loadUrl(Link.base(this) + "/");
    }

    private void askPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            need.add("android.permission.POST_NOTIFICATIONS");
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    private void menu() {
        boolean on = Link.on;
        String[] items = {
                on ? "Switch Jarvis OFF" : "Switch Jarvis ON  (keeps mic + speaker in the background)",
                "Give Jarvis hands  (open Android Accessibility settings)",
                "Set PC address  (now: " + Link.host(this) + ")",
                "Reload"
        };
        new AlertDialog.Builder(this)
                .setTitle("Jarvis Hands")
                .setItems(items, (d, i) -> {
                    if (i == 0) { if (on) stopVoice(); else startVoice(); }
                    else if (i == 1) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    else if (i == 2) setHost(false);
                    else load();
                })
                .show();
    }

    private void setHost(final boolean first) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setHint("192.168.1.4:8796");
        e.setText(Link.host(this));
        new AlertDialog.Builder(this)
                .setTitle("Your PC's address")
                .setMessage("On the PC, the Jarvis window's LOG panel shows the phone link. Type the part after http:// here.")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    String h = e.getText().toString().trim()
                            .replaceFirst("^https?://", "").replaceAll("/+$", "");
                    if (!h.isEmpty()) Link.prefs(this).edit().putString("host", h).apply();
                    load();
                })
                .setCancelable(!first)
                .show();
    }

    private void startVoice() {
        Intent i = new Intent(this, VoiceService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "Jarvis is on. Talk any time; press STOP in the notification to switch him off.",
                Toast.LENGTH_LONG).show();
    }

    private void stopVoice() {
        stopService(new Intent(this, VoiceService.class));
        Toast.makeText(this, "Jarvis switched off.", Toast.LENGTH_SHORT).show();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && web.canGoBack()) { web.goBack(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
    }
}
