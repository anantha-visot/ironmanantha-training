package io.visot.pulsecaller;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements HrService.Listener {

    private static final int BG = Color.parseColor("#0D1214");
    private static final int PANEL = Color.parseColor("#151D20");
    private static final int FG = Color.parseColor("#E7EEF0");
    private static final int MUTED = Color.parseColor("#8A9CA1");
    private static final int ACCENT = Color.parseColor("#4FD1C5");
    private static final int HIGH = Color.parseColor("#FF7A59");
    private static final int LOW = Color.parseColor("#6AA8FF");

    private TextView bpmView, zoneView, statusView, avgView, nextView, batteryNote;
    private Button startBtn, batteryBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, dp(24), p, dp(40));
        scroll.addView(root);

        TextView title = text("PULSE CALLER", 20, FG, true);
        title.setLetterSpacing(0.08f);
        root.addView(title);
        statusView = text("Stopped", 14, MUTED, false);
        root.addView(statusView, lp(0, dp(4)));

        // live panel
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(16), dp(20), dp(18));
        panel.setBackground(round(PANEL, 18));
        bpmView = text("--", 96, FG, true);
        bpmView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        panel.addView(bpmView);
        zoneView = text("No signal", 18, MUTED, true);
        panel.addView(zoneView);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        avgView = text("5-min avg  --", 15, MUTED, false);
        nextView = text("Next call  --", 15, MUTED, false);
        nextView.setGravity(Gravity.END);
        row.addView(avgView, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(nextView, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(row, lp(dp(12), 0));
        root.addView(panel, lp(dp(18), 0));

        startBtn = button("Start", ACCENT, Color.parseColor("#06201D"));
        startBtn.setOnClickListener(v -> { if (HrService.running) stopService(); else start(); });
        root.addView(startBtn, lp(dp(16), 0));

        Button test = button("Say heart rate now", PANEL, FG);
        test.setOnClickListener(v -> {
            if (!HrService.running) { toast("Press Start first."); return; }
            startService(new Intent(this, HrService.class).setAction(HrService.ACTION_TEST));
        });
        root.addView(test, lp(dp(10), 0));

        batteryBtn = button("Allow running in background", PANEL, FG);
        batteryBtn.setOnClickListener(v -> askBattery());
        root.addView(batteryBtn, lp(dp(10), 0));
        batteryNote = text("", 13, MUTED, false);
        root.addView(batteryNote, lp(dp(6), 0));

        TextView hint = text("Before Start: turn on Heart Rate Broadcast in the WHOOP app. "
                + "Once it's live you can close this app and lock the screen. "
                + "Stop it from here or from the notification.", 13, MUTED, false);
        root.addView(hint, lp(dp(14), 0));

        // settings
        TextView st = text("SETTINGS", 13, MUTED, true);
        st.setLetterSpacing(0.1f);
        root.addView(st, lp(dp(24), dp(4)));
        root.addView(numField("Call heart rate every (minutes)", Prefs.INTERVAL));
        root.addView(numField("Zone low (BPM)", Prefs.LO));
        root.addView(numField("Zone high (BPM)", Prefs.HI));
        root.addView(numField("Alert after this many seconds out of zone", Prefs.SUSTAIN));
        root.addView(numField("Repeat alert every (seconds)", Prefs.COOLDOWN));
        root.addView(toggle("Zone alerts", Prefs.ZONE_ALERTS));
        root.addView(toggle("Say \"back in zone\"", Prefs.BACK_IN));
        root.addView(text("Defaults (112–131) are a rough Zone 2 estimate for age 33. "
                + "Put in your own zones from WHOOP or a test. Changes apply right away.", 13, MUTED, false), lp(dp(8), 0));

        setContentView(scroll);
    }

    // ---------- start / stop ----------

    private void start() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(need, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(need, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addIfMissing(need, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) addIfMissing(need, Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), 1); return; }
        startForegroundService(new Intent(this, HrService.class));
        ui.postDelayed(this::onUpdate, 300);
    }

    private void stopService() {
        stopService(new Intent(this, HrService.class));
        ui.postDelayed(this::onUpdate, 300);
    }

    private void addIfMissing(List<String> l, String perm) {
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) l.add(perm);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        for (int i = 0; i < perms.length; i++) {
            if (res[i] != PackageManager.PERMISSION_GRANTED && !perms[i].equals(Manifest.permission.POST_NOTIFICATIONS)) {
                toast("Bluetooth permission is needed to read your heart rate.");
                return;
            }
        }
        start();
    }

    private void askBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---------- live updates ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() { onUpdate(); ui.postDelayed(this, 1000); }
    };

    @Override protected void onResume() {
        super.onResume();
        HrService.listener = this;
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        HrService.listener = null;
        ui.removeCallbacks(tick);
    }

    @Override public void onUpdate() {
        runOnUiThread(() -> {
            boolean on = HrService.running;
            startBtn.setText(on ? "Stop" : "Start");
            statusView.setText(HrService.status);
            int v = HrService.bpm;
            bpmView.setText(v > 0 ? String.valueOf(v) : "--");
            String z = HrService.zone;
            if (v <= 0) { zoneView.setText(on ? "Waiting for heart rate" : "No signal"); zoneView.setTextColor(MUTED); }
            else if (z.equals("in")) { zoneView.setText("IN ZONE"); zoneView.setTextColor(ACCENT); }
            else if (z.equals("above")) { zoneView.setText("ABOVE ZONE"); zoneView.setTextColor(HIGH); }
            else { zoneView.setText("BELOW ZONE"); zoneView.setTextColor(LOW); }
            avgView.setText("5-min avg  " + (HrService.avg5 > 0 ? HrService.avg5 : "--"));
            long left = HrService.nextCallAt - System.currentTimeMillis();
            if (on && HrService.nextCallAt > 0 && left >= 0) {
                long s = left / 1000;
                nextView.setText(String.format("Next call  %d:%02d", s / 60, s % 60));
            } else nextView.setText("Next call  --");

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean free = pm.isIgnoringBatteryOptimizations(getPackageName());
            batteryBtn.setText(free ? "Background running allowed ✓" : "Allow running in background");
            batteryNote.setText(free ? "" : "Recommended. Without it, Android may stop the app during long sessions.");
        });
    }

    // ---------- small view helpers ----------

    private View numField(String label, String key) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(label, 13, MUTED, false));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(Prefs.i(this, key)));
        e.setTextColor(FG);
        e.setTextSize(18);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(round(PANEL, 10));
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                try { Prefs.get(MainActivity.this).edit().putInt(key, Integer.parseInt(s.toString().trim())).apply(); }
                catch (NumberFormatException ignored) {}
            }
        });
        box.addView(e, lp(dp(4), 0));
        box.setLayoutParams(lp(dp(12), 0));
        return box;
    }

    private View toggle(String label, String key) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(FG);
        s.setTextSize(16);
        s.setChecked(Prefs.b(this, key));
        s.setOnCheckedChangeListener((CompoundButton c, boolean on) ->
                Prefs.get(this).edit().putBoolean(key, on).apply());
        s.setPadding(0, dp(10), 0, dp(10));
        s.setLayoutParams(lp(dp(8), 0));
        return s;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(fg);
        b.setBackground(round(bg, 12));
        b.setPadding(dp(12), dp(14), dp(12), dp(14));
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = top;
        l.bottomMargin = bottom;
        return l;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
