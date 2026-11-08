package com.lasm.usb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    /**
     * Internal device types (AudioSystem device bits) — these are framework
     * constants, not addresses. Addresses (MACs, USB ports, ALSA cards) are
     * captured live from AudioService at runtime and never hardcoded.
     */
    static final int T_USB_OUT_DEV = 0x4000;
    static final int T_USB_OUT_HEADSET = 0x4000000;
    static final int T_USB_IN_DEV = 0x80001000;
    static final int T_USB_IN_HEADSET = 0x82000000;
    static final int T_BT_A2DP = 0x80;
    static final int T_BT_SCO = 0x10;
    static final int T_BLE_HEADSET = 0x10000000;
    static final int T_BLE_SPEAKER = 0x20000000;
    static final int T_BT_SCO_MIC = 0x80000008;
    static final int T_BLE_MIC = 0x90000000;

    private static final int[] USB_OUT = {T_USB_OUT_HEADSET, T_USB_OUT_DEV};
    private static final int[] USB_IN = {T_USB_IN_DEV, T_USB_IN_HEADSET};
    private static final int[] MIC_IN = {T_USB_IN_DEV, T_USB_IN_HEADSET, T_BT_SCO_MIC, T_BLE_MIC};
    private static final int[] BT_OUT = {T_BT_A2DP, T_BLE_HEADSET, T_BLE_SPEAKER, T_BT_SCO};

    private SharedPreferences prefs;
    private TextView rootChip, rootHint, outStatus, micStatus, btStatus, devicesView;
    private Button btnOutSpeaker, btnOutEarphone, btnMicPhone, btnMicEarphone, btnBtSpeaker, btnBtRestore;
    private ScrollView logScroll;
    private TextView log;
    private CheckBox autoMic, outStateMode;
    private String btName;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean plugged;
    private boolean rootDialogShown;

    private final BroadcastReceiver plugRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            boolean nowPlugged;
            if ("android.intent.action.HEADSET_PLUG".equals(action)) {
                nowPlugged = i.getIntExtra("state", 0) != 0;
            } else if ("android.hardware.usb.action.USB_STATE".equals(action)) {
                nowPlugged = i.getBooleanExtra("connected", false);
            } else {
                return;
            }
            boolean was = plugged;
            plugged = nowPlugged;
            AppLog.i("app", nowPlugged ? "device connected" : "device disconnected");
            storeDevices();
            if (autoMic.isChecked() && nowPlugged && !was) {
                AppLog.i("app", "auto: switching mic to phone");
                execMic("mic phone");
            }
        }
    };

    private final AudioDeviceCallback devCb = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            storeDevices();
            refreshStatuses();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            storeDevices();
            refreshStatuses();
        }
    };

    private final Root.Cb rootCb = new Root.Cb() {
        @Override
        public void onResult(final String s) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    AppLog.i(s.startsWith("$ ") ? "helper" : "root", s);
                    if (s.contains("ROOT_FAIL")) {
                        Toast.makeText(MainActivity.this, "Root denied", Toast.LENGTH_SHORT).show();
                        showRootDialog();
                    }
                    updateChip();
                }
            });
        }
    };

    private final AppLog.Listener logUi = new AppLog.Listener() {
        @Override
        public void onLine(String line) {
            log.append(line.endsWith("\n") ? line : line + "\n");
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppLog.init(this);
        Root.init(this);
        setContentView(R.layout.main);
        prefs = getSharedPreferences("usbroute", 0);

        rootChip = (TextView) findViewById(R.id.rootChip);
        rootHint = (TextView) findViewById(R.id.rootHint);
        outStatus = (TextView) findViewById(R.id.outStatus);
        micStatus = (TextView) findViewById(R.id.micStatus);
        btStatus = (TextView) findViewById(R.id.btStatus);
        devicesView = (TextView) findViewById(R.id.devicesView);
        btnOutSpeaker = (Button) findViewById(R.id.btnOutSpeaker);
        btnOutEarphone = (Button) findViewById(R.id.btnOutEarphone);
        btnMicPhone = (Button) findViewById(R.id.btnMicPhone);
        btnMicEarphone = (Button) findViewById(R.id.btnMicEarphone);
        btnBtSpeaker = (Button) findViewById(R.id.btnBtSpeaker);
        btnBtRestore = (Button) findViewById(R.id.btnBtRestore);
        log = (TextView) findViewById(R.id.log);
        logScroll = (ScrollView) findViewById(R.id.logScroll);
        autoMic = (CheckBox) findViewById(R.id.autoMic);
        outStateMode = (CheckBox) findViewById(R.id.outStateMode);

        autoMic.setChecked(prefs.getBoolean("autoMic", true));
        outStateMode.setChecked(prefs.getBoolean("outStateMode", false));
        autoMic.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton cb, boolean on) {
                prefs.edit().putBoolean("autoMic", on).apply();
            }
        });
        outStateMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton cb, boolean on) {
                prefs.edit().putBoolean("outStateMode", on).apply();
            }
        });

        rootChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Root.check(rootCb);
            }
        });

        setBtn(R.id.btnOutSpeaker, "force speaker");
        setBtn(R.id.btnOutEarphone, "force none");
        setBtn(R.id.btnMicPhone, "mic phone");
        setBtn(R.id.btnMicEarphone, "mic earphone");
        setBtn(R.id.btnBtSpeaker, "bt speaker");
        setBtn(R.id.btnBtRestore, "bt restore");
        setBtn(R.id.btnDebug, "debug");

        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                storeDevices();
                refreshStatuses();
            }
        });
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLog.clear();
            }
        });
        findViewById(R.id.btnShare).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLog.share(MainActivity.this);
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction("android.intent.action.HEADSET_PLUG");
        f.addAction("android.hardware.usb.action.USB_STATE");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(plugRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(plugRx, f);
        }

        ((AudioManager) getSystemService(AUDIO_SERVICE)).registerAudioDeviceCallback(devCb, null);

        updateChip();
        storeDevices();
        refreshStatuses();
        AppLog.i("app", "ready — approve the superuser prompt if it appears");
        // root on open: this is the call that makes Magisk / KernelSU / APatch
        // show their grant dialog, so root is settled before the first action
        Root.check(rootCb);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.attach(logUi);
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppLog.detach();
    }

    private void setBtn(int id, final String cmd) {
        ((Button) findViewById(id)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cmd.startsWith("mic")) {
                    execMic(cmd);
                } else if (cmd.startsWith("bt ")) {
                    execBt(cmd);
                } else if (cmd.startsWith("force") && outStateMode.isChecked()) {
                    // advanced mode: output via device-state on the USB devices
                    storeDevices();
                    String outs = filter(prefs.getString("addrs", ""), USB_OUT);
                    String sub = "force speaker".equals(cmd) ? "out speaker" : "out earphone";
                    runHelper(outs.length() > 0 ? sub + " b64:" + b64(outs) : sub);
                } else {
                    runHelper(cmd);
                }
                prefs.edit().putString("lastCmd", cmd).apply();
                refreshStatuses();
            }
        });
    }

    /** mic ops run only against input devices the framework currently reports */
    private void execMic(String cmd) {
        storeDevices();
        String in = filter(prefs.getString("addrs", ""), MIC_IN);
        runHelper(in.length() > 0 ? cmd + " b64:" + b64(in) : cmd);
    }

    /** bluetooth ops run only against BT output devices the framework currently reports */
    private void execBt(String cmd) {
        storeDevices();
        String outs = filter(prefs.getString("addrs", ""), BT_OUT);
        runHelper(outs.length() > 0 ? cmd + " b64:" + b64(outs) : cmd);
    }

    private void runHelper(String full) {
        AppLog.i("helper", "$ Helper " + full);
        Root.exec(full, rootCb);
    }

    private String b64(String s) {
        return android.util.Base64.encodeToString(s.getBytes(), android.util.Base64.NO_WRAP);
    }

    /** keep only "0xTYPE@addr" entries whose type matches one of the wanted types */
    private String filter(String addrs, int[] types) {
        if (addrs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String e : addrs.split("\\|")) {
            String t = e.contains("@") ? e.substring(0, e.indexOf('@')) : e;
            int v;
            try {
                String hex = t.replace("0x", "").replace("0X", "").trim();
                v = (int) Long.parseLong(hex, 16);
            } catch (Throwable x) {
                continue;
            }
            for (int want : types) {
                if (v == want) {
                    if (sb.length() > 0) sb.append('|');
                    sb.append(e);
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** snapshot every USB / Bluetooth audio device the framework reports, persisted for later ops */
    private void storeDevices() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            AudioDeviceInfo[] all = am.getDevices(AudioManager.GET_DEVICES_ALL);
            StringBuilder addrs = new StringBuilder();
            StringBuilder disp = new StringBuilder();
            btName = null;
            for (AudioDeviceInfo d : all) {
                int t = internalType(d);
                boolean usb = contains(USB_OUT, t) || contains(USB_IN, t);
                boolean btx = contains(BT_OUT, t) || t == T_BT_SCO_MIC || t == T_BLE_MIC;
                if (!usb && !btx) continue;
                String a = d.getAddress();
                if (a == null || a.isEmpty()) continue;
                if (addrs.length() > 0) addrs.append('|');
                addrs.append("0x").append(Integer.toHexString(t)).append('@').append(a);
                if (disp.length() > 0) disp.append('\n');
                disp.append(d.isSource() ? "IN " : "OUT").append(' ')
                        .append(label(t)).append("  addr=").append(a)
                        .append("  (").append(d.getProductName()).append(')');
                if (btx && btName == null) btName = String.valueOf(d.getProductName());
            }
            if (addrs.length() > 0) {
                prefs.edit().putString("addrs", addrs.toString()).apply();
            }
            devicesView.setText(disp.length() > 0 ? disp.toString()
                    : "no USB / Bluetooth audio device reported — plug one in");
            refreshStatuses();
        } catch (Throwable t) {
            AppLog.e("app", "storeDevices: " + t);
        }
    }

    private static String label(int t) {
        switch (t) {
            case T_USB_OUT_DEV: return "usb-out   ";
            case T_USB_OUT_HEADSET: return "usb-hs-out";
            case T_USB_IN_DEV: return "usb-mic   ";
            case T_USB_IN_HEADSET: return "usb-hs-mic";
            case T_BT_A2DP: return "bt-a2dp   ";
            case T_BT_SCO: return "bt-sco    ";
            case T_BLE_HEADSET: return "ble-headset";
            case T_BLE_SPEAKER: return "ble-speaker";
            case T_BT_SCO_MIC: return "bt-sco-mic";
            case T_BLE_MIC: return "ble-mic   ";
            default: return "0x" + Integer.toHexString(t);
        }
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) return true;
        }
        return false;
    }

    private static int internalType(AudioDeviceInfo d) {
        try {
            return ((Integer) AudioDeviceInfo.class.getMethod("getInternalType").invoke(d)).intValue();
        } catch (Throwable t) {
            int pub = d.getType();
            boolean in = d.isSource();
            switch (pub) {
                case 12: return in ? 0x80000800 : 0x2000;     // TYPE_USB_ACCESSORY
                case 13: return in ? 0x80001000 : 0x4000;     // TYPE_USB_DEVICE
                case 22: return in ? 0x82000000 : 0x4000000;  // TYPE_USB_HEADSET
                case 7: return in ? 0x80000008 : 0x10;        // TYPE_BLUETOOTH_SCO
                case 8: return 0x80;                          // TYPE_BLUETOOTH_A2DP
                case 26: return in ? 0x90000000 : 0x10000000; // TYPE_BLE_HEADSET
                case 27: return 0x20000000;                   // TYPE_BLE_SPEAKER
                case 28: return 0x40000000;                   // TYPE_BLE_BROADCAST
                default: return in ? (0x80000000 | pub) : pub;
            }
        }
    }

    /** recolor status lines and highlight the active button of each pair */
    private void refreshStatuses() {
        String last = prefs.getString("lastCmd", "");
        boolean outSpk = "force speaker".equals(last);
        boolean micPhone = "mic phone".equals(last);
        boolean btSpk = "bt speaker".equals(last);

        markActive(btnOutSpeaker, outSpk);
        markActive(btnOutEarphone, "force none".equals(last));
        outStatus.setText(outSpk ? "●  output → phone speaker"
                : "●  output → default routing");
        outStatus.setTextColor(getColor(outSpk ? R.color.accent : R.color.subtext));

        markActive(btnMicPhone, micPhone);
        markActive(btnMicEarphone, "mic earphone".equals(last));
        micStatus.setText(micPhone ? "●  mic → phone (earphone/BT mic muted)"
                : "●  mic → default routing");
        micStatus.setTextColor(getColor(micPhone ? R.color.accent : R.color.subtext));

        markActive(btnBtSpeaker, btSpk);
        markActive(btnBtRestore, "bt restore".equals(last));
        boolean btOn = btName != null && !btName.isEmpty();
        btStatus.setText(btOn ? "●  bluetooth → " + btName : "●  bluetooth → none");
        btStatus.setTextColor(getColor(btOn ? R.color.ok : R.color.subtext));
    }

    private void markActive(Button b, boolean active) {
        b.setBackgroundResource(active ? R.drawable.bg_btn_accent : R.drawable.bg_btn);
    }

    private void updateChip() {
        int s = Root.state();
        if (s == Root.CHECKING) {
            rootChip.setBackgroundResource(R.drawable.bg_chip_wait);
            rootChip.setTextColor(getColor(R.color.warn));
            rootChip.setText("root · waiting for approval…");
        } else if (s == Root.GRANTED) {
            rootChip.setBackgroundResource(R.drawable.bg_chip_ok);
            rootChip.setTextColor(getColor(R.color.ok));
            rootChip.setText("root · granted");
        } else if (s == Root.DENIED) {
            rootChip.setBackgroundResource(R.drawable.bg_chip_bad);
            rootChip.setTextColor(getColor(R.color.bad));
            rootChip.setText("root · denied — tap to retry");
        } else {
            rootChip.setBackgroundResource(R.drawable.bg_chip_idle);
            rootChip.setTextColor(getColor(R.color.subtext));
            rootChip.setText("root · tap to check");
        }
        rootHint.setText("pkg " + getPackageName() + " · " + Root.suPath());
    }

    /** Same flow root apps rely on: su is invoked, the manager shows its own
     *  grant dialog; if it is denied or missed, explain and offer a retry. */
    private void showRootDialog() {
        if (rootDialogShown) return;
        rootDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle("Superuser access needed")
                .setMessage("USB Audio Router changes audio routing through the system audio "
                        + "service, which only root can do.\n\n"
                        + "• When the Magisk / KernelSU / APatch prompt appears, tap Grant.\n"
                        + "• Missed it? Open your root manager, grant this app, then tap Retry.")
                .setPositiveButton("Retry", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Root.check(rootCb);
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(plugRx);
            ((AudioManager) getSystemService(AUDIO_SERVICE)).unregisterAudioDeviceCallback(devCb);
        } catch (Throwable ignored) {
        }
    }
}
