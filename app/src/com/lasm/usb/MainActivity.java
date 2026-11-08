package com.lasm.usb;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView outStatus, micStatus, devicesView, log;
    private ScrollView logScroll;
    private CheckBox autoMic, outStateMode;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean plugged;

    private final BroadcastReceiver plugRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            boolean nowPlugged = plugged;
            if ("android.intent.action.HEADSET_PLUG".equals(action)) {
                nowPlugged = i.getIntExtra("state", 0) != 0;
            } else if ("android.hardware.usb.action.USB_STATE".equals(action)) {
                nowPlugged = i.getBooleanExtra("connected", false);
            } else {
                return;
            }
            boolean was = plugged;
            plugged = nowPlugged;
            appendLog(nowPlugged ? "device connected" : "device disconnected");
            if (nowPlugged) storeDevices();
            if (autoMic.isChecked() && nowPlugged && !was) {
                appendLog("auto: switching mic to phone");
                execMic("mic phone");
            }
        }
    };

    private final AudioDeviceCallback devCb = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            storeDevices();
        }
    };

    private final Root.Cb uiCb = new Root.Cb() {
        @Override
        public void onResult(final String s) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    appendLog(s);
                    if (s.contains("ROOT_FAIL")) {
                        Toast.makeText(MainActivity.this, "Root denied", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.main);
        prefs = getSharedPreferences("usbroute", 0);
        Root.init(this);

        outStatus = (TextView) findViewById(R.id.outStatus);
        micStatus = (TextView) findViewById(R.id.micStatus);
        devicesView = (TextView) findViewById(R.id.devicesView);
        log = (TextView) findViewById(R.id.log);
        logScroll = (ScrollView) findViewById(R.id.logScroll);
        autoMic = (CheckBox) findViewById(R.id.autoMic);
        autoMic.setChecked(prefs.getBoolean("autoMic", true));
        outStateMode = (CheckBox) findViewById(R.id.outStateMode);
        outStateMode.setChecked(prefs.getBoolean("outStateMode", false));
        outStateMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("outStateMode", outStateMode.isChecked()).apply();
            }
        });

        setBtn(R.id.btnOutSpeaker, "force speaker");
        setBtn(R.id.btnOutEarphone, "force none");
        setBtn(R.id.btnMicPhone, "mic phone");
        setBtn(R.id.btnMicEarphone, "mic earphone");
        setBtn(R.id.btnDebug, "debug");

        findViewById(R.id.autoMic).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("autoMic", autoMic.isChecked()).apply();
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction("android.intent.action.HEADSET_PLUG");
        f.addAction("android.hardware.usb.action.USB_STATE");
        registerReceiver(plugRx, f);

        ((AudioManager) getSystemService(AUDIO_SERVICE)).registerAudioDeviceCallback(devCb, null);

        storeDevices();
        refreshStatuses();
        appendLog("ready — grant root on the first command if asked");
    }

    private void setBtn(int id, final String cmd) {
        ((Button) findViewById(id)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cmd.startsWith("mic")) {
                    execMic(cmd);
                } else if (cmd.startsWith("force") && outStateMode.isChecked()) {
                    storeDevices();
                    String outs = filterOutputs(prefs.getString("addrs", ""));
                    String sub = "speaker".equals(cmd.replace("force ", ""))
                            ? "out speaker" : "out earphone";
                    String full = outs.length() > 0 ? sub + " b64:" + b64(outs) : sub;
                    appendLog("$ Helper " + full);
                    Root.exec(full, uiCb);
                } else {
                    appendLog("$ Helper " + cmd);
                    Root.exec(cmd, uiCb);
                }
                prefs.edit().putString("lastCmd", cmd).apply();
                refreshStatuses();
            }
        });
    }

    /** mic ops only run against input types the framework currently reports */
    private void execMic(String cmd) {
        storeDevices();
        String in = filterInputs(prefs.getString("addrs", ""));
        String full = in.length() > 0 ? cmd + " b64:" + b64(in) : cmd;
        appendLog("$ Helper " + cmd + " [" + in + "]");
        Root.exec(full, uiCb);
    }

    private String b64(String s) {
        return android.util.Base64.encodeToString(s.getBytes(), android.util.Base64.NO_WRAP);
    }

    private String filterInputs(String addrs) {
        return filter(addrs, "0x80001000", "0x82000000");
    }

    private String filterOutputs(String addrs) {
        return filter(addrs, "0x4000", "0x4000000");
    }

    private String filter(String addrs, String... types) {
        if (addrs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String e : addrs.split("\\|")) {
            String t = e.contains("@") ? e.substring(0, e.indexOf('@')) : e;
            for (String want : types) {
                if (t.equals(want)) {
                    if (sb.length() > 0) sb.append('|');
                    sb.append(e);
                }
            }
        }
        return sb.toString();
    }

    /** snapshot every USB audio device the framework reports, persisted for later ops */
    private void storeDevices() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            AudioDeviceInfo[] all = am.getDevices(AudioManager.GET_DEVICES_ALL);
            StringBuilder addrs = new StringBuilder();
            StringBuilder disp = new StringBuilder();
            for (AudioDeviceInfo d : all) {
                int t = internalType(d);
                boolean usb = t == 0x4000 || t == 0x4000000
                        || t == 0x80001000 || t == 0x82000000;
                if (!usb) continue;
                String a = d.getAddress();
                if (a == null || a.isEmpty()) continue;
                if (addrs.length() > 0) addrs.append('|');
                addrs.append("0x").append(Integer.toHexString(t)).append('@').append(a);
                if (disp.length() > 0) disp.append('\n');
                disp.append(d.isSource() ? "IN " : "OUT").append(" 0x").append(Integer.toHexString(t))
                    .append("  addr=").append(a)
                    .append("  (").append(d.getProductName()).append(")");
            }
            if (addrs.length() > 0) {
                prefs.edit().putString("addrs", addrs.toString()).apply();
            }
            devicesView.setText(disp.length() > 0
                    ? "stored devices:\n" + disp
                    : "stored devices: none — plug the earphone in");
        } catch (Throwable t) {
            appendLog("storeDevices: " + t);
        }
    }

    private static int internalType(AudioDeviceInfo d) {
        try {
            return ((Integer) AudioDeviceInfo.class.getMethod("getInternalType").invoke(d)).intValue();
        } catch (Throwable t) {
            int pub = d.getType();
            boolean in = d.isSource();
            switch (pub) {
                case 12: return in ? 0x80000800 : 0x2000;    // TYPE_USB_ACCESSORY
                case 13: return in ? 0x80001000 : 0x4000;    // TYPE_USB_DEVICE
                case 22: return in ? 0x82000000 : 0x4000000; // TYPE_USB_HEADSET
                default: return in ? (0x80000000 | pub) : pub;
            }
        }
    }

    private void refreshStatuses() {
        String last = prefs.getString("lastCmd", "");
        outStatus.setText("force speaker".equals(last) ? "output: forced speaker"
                : "output: default routing");
        micStatus.setText("mic phone".equals(last) ? "mic: phone (earphone mic faked out)"
                : "mic: default routing");
    }

    private void appendLog(String s) {
        log.append(s + "\n");
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
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
