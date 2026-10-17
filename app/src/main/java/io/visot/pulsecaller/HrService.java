package io.visot.pulsecaller;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

/**
 * Foreground service that stays alive with the screen off and the app closed.
 * Connects to any Bluetooth heart-rate strap (WHOOP with Heart Rate Broadcast on),
 * speaks the BPM on a fixed interval, and speaks zone alerts.
 */
@SuppressLint("MissingPermission")
public class HrService extends Service {

    static final String ACTION_STOP = "io.visot.pulsecaller.STOP";
    static final String ACTION_TEST = "io.visot.pulsecaller.TEST";

    static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    static final UUID HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL = "pulse";
    private static final int NOTIF_ID = 7;
    private static final long FIVE_MIN = 5 * 60_000L;

    /** Screen listener; only one screen at a time. */
    interface Listener { void onUpdate(); }
    static volatile Listener listener;
    static volatile boolean running;
    static volatile int bpm = -1;
    static volatile int avg5 = -1;
    static volatile String zone = "";
    static volatile String status = "Stopped";
    static volatile long nextCallAt = 0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning, connected, stopping, lostAnnounced, everConnected;
    private TextToSpeech tts;
    private boolean ttsReady;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wake;

    // reading state
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();
    private long lastCall, lastReading, outSince, lastAlert, lastNotif;
    private String outDir = null;
    private boolean alertedOut;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }

        if (!running) {
            try {
                Notification n = buildNotification("Starting…");
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                } else {
                    startForeground(NOTIF_ID, n);
                }
            } catch (Exception e) {
                stopSelf();
                return START_NOT_STICKY;
            }
            running = true;
            stopping = false;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseCaller:hr");
            wake.acquire();
            audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            initTts();
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                setStatus("Bluetooth is off. Turn it on and press Start.");
                main.postDelayed(this::stopSelf, 4000);
            } else {
                startScan();
            }
            main.postDelayed(watchdog, 10_000);
        }

        if (ACTION_TEST.equals(action)) {
            say(bpm > 0 ? "Heart rate " + bpm + "." : "Voice test. No heart rate yet.", true);
        }
        return START_STICKY;
    }

    // ---------------- Bluetooth ----------------

    private void startScan() {
        if (stopping || scanning || connected) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { retryLater(5000); return; }
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HR_SERVICE)).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanStart = System.currentTimeMillis();
            scanner.startScan(Collections.singletonList(f), s, scanCb);
            scanning = true;
            setStatus("Looking for your strap…");
        } catch (Exception e) {
            setStatus("Can't scan: " + e.getMessage());
            retryLater(8000);
        }
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
        }
        scanning = false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String saved = Prefs.get(HrService.this).getString(Prefs.ADDR, "");
            // Prefer the strap used last time; otherwise take the first heart-rate device found.
            if (!saved.isEmpty() && !saved.equals(d.getAddress()) && System.currentTimeMillis() - scanStart < 8000) return;
            main.post(() -> connect(d));
        }
        @Override public void onScanFailed(int code) {
            scanning = false;
            setStatus("Scan failed (" + code + "), retrying");
            retryLater(8000);
        }
    };
    private long scanStart = System.currentTimeMillis();

    private void connect(BluetoothDevice d) {
        if (connected || gatt != null || stopping) return;
        stopScan();
        String name = d.getName();
        setStatus("Connecting to " + (name == null ? "strap" : name) + "…");
        Prefs.get(this).edit().putString(Prefs.ADDR, d.getAddress()).apply();
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post(() -> onLost(g));
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService svc = g.getService(HR_SERVICE);
            BluetoothGattCharacteristic ch = svc == null ? null : svc.getCharacteristic(HR_MEASUREMENT);
            if (ch == null) { g.disconnect(); return; }
            g.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor d = ch.getDescriptor(CCCD);
            if (d != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            String name = g.getDevice().getName();
            main.post(() -> {
                connected = true;
                setStatus("Live: " + (name == null ? "heart rate strap" : name));
                if (!everConnected) {
                    everConnected = true;
                    lastCall = System.currentTimeMillis();
                    say("Connected. I will call your heart rate every " + Prefs.i(HrService.this, Prefs.INTERVAL) + " minutes.", false);
                } else if (lostAnnounced) {
                    say("Heart rate reconnected.", false);
                }
                lostAnnounced = false;
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            handle(value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (Build.VERSION.SDK_INT < 33) handle(ch.getValue());
        }

        private void handle(byte[] v) {
            if (v == null || v.length < 2) return;
            int hr = (v[0] & 0x01) != 0 && v.length >= 3
                    ? (v[1] & 0xFF) | ((v[2] & 0xFF) << 8)
                    : (v[1] & 0xFF);
            if (hr <= 0) return;
            main.post(() -> onReading(hr));
        }
    };

    private void onLost(BluetoothGatt g) {
        try { g.close(); } catch (Exception ignored) {}
        if (g == gatt) gatt = null;
        connected = false;
        if (stopping) return;
        setStatus("Signal lost, reconnecting…");
        if (!lostAnnounced && everConnected) { lostAnnounced = true; say("Heart rate signal lost. Reconnecting.", true); }
        retryLater(3000);
    }

    private void retryLater(long ms) {
        main.postDelayed(() -> { if (!stopping && !connected && gatt == null) { scanStart = System.currentTimeMillis(); startScan(); } }, ms);
    }

    /** Catches a strap that stays "connected" but stops sending readings. */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            if (connected && lastReading > 0 && System.currentTimeMillis() - lastReading > 25_000) {
                if (!lostAnnounced) { lostAnnounced = true; say("No heart rate reading.", true); }
                if (gatt != null) gatt.disconnect();
            }
            main.postDelayed(this, 10_000);
        }
    };

    // ---------------- readings + speech logic ----------------

    private void onReading(int v) {
        long now = System.currentTimeMillis();
        lastReading = now;
        samples.addLast(new long[]{now, v});
        while (!samples.isEmpty() && now - samples.peekFirst()[0] > FIVE_MIN) samples.removeFirst();
        long sum = 0;
        for (long[] s : samples) sum += s[1];
        int avg = (int) Math.round((double) sum / samples.size());

        int lo = Prefs.i(this, Prefs.LO), hi = Prefs.i(this, Prefs.HI);
        String z = v > hi ? "above" : v < lo ? "below" : "in";
        bpm = v; avg5 = avg; zone = z;

        long interval = Prefs.i(this, Prefs.INTERVAL) * 60_000L;
        if (lastCall == 0) lastCall = now;
        if (now - lastCall >= interval) {
            lastCall = now;
            say("Heart rate " + v + ". Five minute average " + avg + ".", false);
        }
        nextCallAt = lastCall + interval;

        if (Prefs.b(this, Prefs.ZONE_ALERTS)) {
            if (z.equals("in")) {
                if (alertedOut && Prefs.b(this, Prefs.BACK_IN)) say("Back in zone. " + v + ".", true);
                outDir = null; alertedOut = false;
            } else {
                if (!z.equals(outDir)) { outDir = z; outSince = now; alertedOut = false; }
                long sustain = Prefs.i(this, Prefs.SUSTAIN) * 1000L;
                long cooldown = Prefs.i(this, Prefs.COOLDOWN) * 1000L;
                if (now - outSince >= sustain && now - lastAlert >= cooldown) {
                    lastAlert = now; alertedOut = true;
                    say(z.equals("above") ? "Above zone. " + v + ". Ease off." : "Below zone. " + v + ". Pick it up.", true);
                }
            }
        }

        if (now - lastNotif > 5000) {
            lastNotif = now;
            String zt = z.equals("in") ? "in zone" : z + " zone";
            updateNotification(v + " BPM · " + zt + " · 5-min avg " + avg);
        }
        Listener l = listener;
        if (l != null) l.onUpdate();
    }

    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), s -> {
            if (s != TextToSpeech.SUCCESS) return;
            tts.setLanguage(Locale.US);
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { releaseFocus(); }
                @Override public void onError(String id) { releaseFocus(); }
            });
            ttsReady = true;
            if (pending != null) { String p = pending; pending = null; say(p, false); }
        });
    }
    private String pending;

    private void say(String text, boolean urgent) {
        if (!ttsReady) { pending = text; return; }
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build();
        }
        audio.requestAudioFocus(focus);   // ducks music while speaking
        tts.speak(text, urgent ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, new Bundle(), "u" + System.nanoTime());
    }

    private void releaseFocus() {
        if (audio != null && focus != null && tts != null && !tts.isSpeaking()) audio.abandonAudioFocusRequest(focus);
    }

    // ---------------- notification + status ----------------

    private void setStatus(String s) {
        status = s;
        if (!connected) updateNotification(s);
        Listener l = listener;
        if (l != null) l.onUpdate();
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Heart rate", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, HrService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle("Pulse Caller")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_heart), "Stop", stop).build())
                .build();
    }

    private void updateNotification(String text) {
        if (!running) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        main.removeCallbacksAndMessages(null);
        stopScan();
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {} gatt = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
        if (wake != null && wake.isHeld()) wake.release();
        bpm = -1; avg5 = -1; zone = ""; status = "Stopped"; nextCallAt = 0;
        Listener l = listener;
        if (l != null) l.onUpdate();
        super.onDestroy();
    }
}
