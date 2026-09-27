package com.jarvis.hands;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

/** Jarvis's hands on the phone. While Dr Wolf has Jarvis switched ON (Link.on), this asks the PC
 *  for one phone action at a time and carries it out with Android's accessibility tools: reading
 *  the screen, tapping, typing, scrolling, opening an app, the Back/Home buttons. It refuses
 *  banking and payment apps, and never types into a password, PIN or OTP box. */
public class HandsService extends AccessibilityService {
    private volatile boolean looping = false;

    private static final String[] BLOCK_APP = {
            "bank", "upi", "paytm", "phonepe", "gpay", "googlepay", "wallet", "bhim",
            "axis", "hdfc", "icici", "sbi", "kotak", "yesbank", "amazonpay"
    };
    private static final String[] SECRET = {
            "password", "passcode", "pin", "otp", "cvv", "card number", "security code", "mpin", "upi pin"
    };

    @Override protected void onServiceConnected() {
        Link.status = "hands ready";
        if (!looping) {
            looping = true;
            new Thread(this::loop, "jarvis-hands").start();
        }
    }

    private void loop() {
        while (looping) {
            try {
                if (!Link.on) { Thread.sleep(700); continue; }
                String job = Link.get(this, "/api/phone/pull", 30000);   // waits until Jarvis has a phone action
                if (job == null || job.trim().isEmpty()) continue;
                JSONObject j = new JSONObject(job);
                String id = j.optString("id");
                if (id.isEmpty()) continue;
                JSONObject out = run(j).put("id", id);
                Link.postJson(this, "/api/phone/result", out, 15000);
            } catch (Exception e) {
                try { Thread.sleep(1200); } catch (InterruptedException ignore) { return; }
            }
        }
    }

    private JSONObject run(JSONObject j) {
        JSONObject r = new JSONObject();
        try {
            String tool = j.optString("tool");
            JSONObject a = j.optJSONObject("args");
            if (a == null) a = new JSONObject();
            if (!"phone_open_app".equals(tool) && blockedApp())
                return err(r, "I won't act here, sir — this looks like a banking or payment app.");
            switch (tool) {
                case "phone_read_screen": return r.put("text", readScreen());
                case "phone_open_app":    return openApp(a.optString("name"), r);
                case "phone_tap_text":    return tap(a.optString("text"), r);
                case "phone_type":        return type(a.optString("text"), r);
                case "phone_scroll":      return scroll(a.optString("direction", "down"), r);
                case "phone_button":      return button(a.optString("which", "back"), r);
                default:                  return err(r, "Unknown phone action: " + tool);
            }
        } catch (Exception e) {
            return err(r, "the phone action failed: " + e);
        }
    }

    private static JSONObject err(JSONObject r, String msg) {
        try { r.put("error", true).put("text", msg); } catch (Exception ignore) {}
        return r;
    }

    // ---------- safety ----------
    private boolean blockedApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence p = root != null ? root.getPackageName() : null;
        String pkg = p != null ? p.toString().toLowerCase() : "";
        for (String b : BLOCK_APP) if (pkg.contains(b)) return true;
        return false;
    }

    private static boolean secretField(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isPassword()) return true;
        CharSequence h = n.getHintText(), t = n.getText();
        String s = ((h == null ? "" : h) + " " + (t == null ? "" : t)).toLowerCase();
        for (String k : SECRET) if (s.contains(k)) return true;
        return false;
    }

    // ---------- reading ----------
    private String readScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "I can't see the screen just now.";
        StringBuilder sb = new StringBuilder("On screen");
        CharSequence pkg = root.getPackageName();
        if (pkg != null) sb.append(" (").append(pkg).append(")");
        sb.append(":\n");
        collect(root, sb, 0);
        String s = sb.toString();
        return s.length() > 6000 ? s.substring(0, 6000) + "\n…(more below)" : s;
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        String label = t != null ? t.toString() : (d != null ? d.toString() : "");
        if (label.trim().length() > 0) {
            sb.append(n.isClickable() ? "• [tap] " : "• ").append(label.trim());
            if (n.isEditable()) sb.append("  <text box>");
            sb.append("\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo n, String text, boolean editable) {
        if (n == null) return null;
        if (editable) {
            if (n.isEditable()) return n;
        } else {
            CharSequence t = n.getText(), d = n.getContentDescription();
            String s = ((t == null ? "" : t) + " " + (d == null ? "" : d)).toLowerCase();
            if (s.contains(text.toLowerCase()) && n.isVisibleToUser()) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = find(n.getChild(i), text, editable);
            if (r != null) return r;
        }
        return null;
    }

    // ---------- actions ----------
    private JSONObject tap(String text, JSONObject r) throws Exception {
        AccessibilityNodeInfo hit = find(getRootInActiveWindow(), text, false);
        if (hit == null) return err(r, "I can't find \"" + text + "\" on screen.");
        AccessibilityNodeInfo c = hit;
        while (c != null && !c.isClickable()) c = c.getParent();
        if (c != null) { c.performAction(AccessibilityNodeInfo.ACTION_CLICK); return r.put("text", "Tapped \"" + text + "\"."); }
        Rect b = new Rect();
        hit.getBoundsInScreen(b);
        tapAt(b.centerX(), b.centerY());
        return r.put("text", "Tapped \"" + text + "\".");
    }

    private JSONObject type(String text, JSONObject r) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo box = root != null ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
        if (box == null) box = find(root, "", true);
        if (box == null) return err(r, "I can't find a text box to type in.");
        if (secretField(box)) return err(r, "I won't type here, sir — this looks like a password, PIN or OTP box.");
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        return ok ? r.put("text", "Typed: " + text) : err(r, "the box wouldn't take the text.");
    }

    private JSONObject scroll(String dir, JSONObject r) throws Exception {
        int h = getResources().getDisplayMetrics().heightPixels;
        int w = getResources().getDisplayMetrics().widthPixels;
        boolean up = "up".equals(dir);
        float y1 = up ? h * 0.28f : h * 0.72f;
        float y2 = up ? h * 0.72f : h * 0.28f;
        Path p = new Path();
        p.moveTo(w / 2f, y1);
        p.lineTo(w / 2f, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 300)).build();
        dispatchGesture(g, null, null);
        return r.put("text", "Scrolled " + dir + ".");
    }

    private void tapAt(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        dispatchGesture(g, null, null);
    }

    private JSONObject button(String which, JSONObject r) throws Exception {
        int action;
        switch (which) {
            case "home":          action = GLOBAL_ACTION_HOME; break;
            case "recents":       action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            default:              action = GLOBAL_ACTION_BACK;
        }
        performGlobalAction(action);
        return r.put("text", which + " done.");
    }

    private JSONObject openApp(String name, JSONObject r) throws Exception {
        if (name == null || name.trim().isEmpty()) return err(r, "Which app should I open?");
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String want = name.toLowerCase();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String label = ri.loadLabel(pm).toString();
            String pkg = ri.activityInfo.packageName.toLowerCase();
            for (String b : BLOCK_APP) if (pkg.contains(b) || label.toLowerCase().contains(b))
                return err(r, "I won't open " + label + ", sir — it's a banking or payment app.");
            if (label.toLowerCase().contains(want)) {
                Intent launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                    return r.put("text", "Opened " + label + ".");
                }
            }
        }
        return err(r, "I can't find an app called \"" + name + "\".");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { looping = false; super.onDestroy(); }
}
