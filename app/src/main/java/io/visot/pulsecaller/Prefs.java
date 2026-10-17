package io.visot.pulsecaller;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared by the screen and the background service. */
final class Prefs {
    static final String INTERVAL = "interval_min";   // spoken BPM call, minutes
    static final String LO = "zone_lo";               // zone low, BPM
    static final String HI = "zone_hi";               // zone high, BPM
    static final String SUSTAIN = "sustain_s";        // seconds out of zone before alert
    static final String COOLDOWN = "cooldown_s";      // seconds between repeat alerts
    static final String ZONE_ALERTS = "zone_alerts";
    static final String BACK_IN = "back_in";
    static final String ADDR = "strap_addr";          // last strap used

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("pulse_caller", Context.MODE_PRIVATE);
    }

    static int i(Context c, String key) {
        SharedPreferences p = get(c);
        switch (key) {
            case INTERVAL: return clamp(p.getInt(key, 5), 1, 60);
            case LO: return clamp(p.getInt(key, 112), 40, 220);
            case HI: return clamp(p.getInt(key, 131), 40, 230);
            case SUSTAIN: return clamp(p.getInt(key, 20), 3, 300);
            case COOLDOWN: return clamp(p.getInt(key, 60), 10, 900);
            default: return p.getInt(key, 0);
        }
    }

    static boolean b(Context c, String key) {
        return get(c).getBoolean(key, true);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private Prefs() {}
}
