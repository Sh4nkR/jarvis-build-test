package com.jarvis.hands;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Plain HTTP to Jarvis on the PC (same Wi-Fi). The phone asks Jarvis for a phone action to do
 *  (long-poll) and posts back what happened. Nothing else is stored or sent. */
final class Link {
    private Link() {}

    /** true only while Dr Wolf has Jarvis switched ON in this app (the foreground service is up).
     *  Jarvis can see or operate the phone only while this is true. */
    static volatile boolean on = false;
    static volatile String status = "off";

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    static String host(Context c) {
        return prefs(c).getString("host", "192.168.1.4:8796");   // the PC's HTTPS port for the app
    }

    static String base(Context c) {
        String h = host(c).trim();
        if (h.startsWith("http://") || h.startsWith("https://")) return h;
        return "https://" + h;                          // the app talks to Jarvis over HTTPS
    }

    private static HttpURLConnection open(Context c, String path, int timeoutMs) throws IOException {
        URL u = new URL(base(c) + path);
        HttpURLConnection h = (HttpURLConnection) u.openConnection();
        // The user typed their own PC's LAN address. Jarvis serves it with a self-signed
        // certificate, so trust that connection to the PC (no password is ever sent over it).
        if (h instanceof HttpsURLConnection) {
            ((HttpsURLConnection) h).setSSLSocketFactory(trustPc());
            ((HttpsURLConnection) h).setHostnameVerifier((name, session) -> true);
        }
        h.setConnectTimeout(6000);
        h.setReadTimeout(timeoutMs);
        h.setUseCaches(false);
        h.setRequestProperty("Origin", base(c));        // Jarvis only accepts his own pages
        return h;
    }

    private static javax.net.ssl.SSLSocketFactory pc;

    private static synchronized javax.net.ssl.SSLSocketFactory trustPc() {
        if (pc != null) return pc;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            pc = ctx.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return pc;
    }

    private static String read(HttpURLConnection h) throws IOException {
        int code = h.getResponseCode();
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
        }
        String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) throw new IOException("Jarvis answered " + code + ": " + body);
        return body;
    }

    static String get(Context c, String path, int timeoutMs) throws IOException {
        HttpURLConnection h = open(c, path, timeoutMs);
        try {
            return read(h);
        } finally {
            h.disconnect();
        }
    }

    static String postJson(Context c, String path, JSONObject j, int timeoutMs) throws IOException {
        HttpURLConnection h = open(c, path, timeoutMs);
        try {
            h.setDoOutput(true);
            h.setRequestMethod("POST");
            h.setRequestProperty("Content-Type", "application/json");
            byte[] body = j.toString().getBytes(StandardCharsets.UTF_8);
            h.setFixedLengthStreamingMode(body.length);
            OutputStream o = h.getOutputStream();
            o.write(body);
            o.close();
            return read(h);
        } finally {
            h.disconnect();
        }
    }
}
