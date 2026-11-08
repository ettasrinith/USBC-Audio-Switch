package com.lasm.usb;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Entry point when launched through app_process as root.
 *
 * usage: Helper out speaker|earphone [addrs]
 *               mic phone|earphone [addrs]
 *               bt speaker|restore [addrs]
 *               force speaker|headphones|none
 *               force-rec speaker|none
 *               debug
 *
 * addrs is a base64 encoded list of "0xTYPE@address" entries so the command
 * survives shell quoting.
 */
public class Helper {

    static final int OUT_USB_DEVICE = 0x4000;
    static final int OUT_USB_HEADSET = 0x4000000;
    static final int IN_USB_DEVICE = 0x80001000;   // DEVICE_BIT_IN | 0x1000
    static final int IN_USB_HEADSET = 0x82000000;  // DEVICE_BIT_IN | 0x2000000
    static final int IN_BUILTIN_MIC = 0x80000004;  // DEVICE_BIT_IN | 0x4
    static final int OUT_BT_A2DP = 0x80;           // classic A2DP output
    static final int OUT_BT_SCO = 0x10;            // hfp/hsp output
    static final int OUT_BLE_HEADSET = 0x10000000; // LE audio headset
    static final int OUT_BLE_SPEAKER = 0x20000000; // LE audio speaker
    static final int IN_BT_SCO_HEADSET = 0x80000008;  // DEVICE_BIT_IN | 0x8
    static final int IN_BLE_HEADSET = 0x90000000;     // DEVICE_BIT_IN | 0x10000000
    static final int STATE_UNAVAILABLE = 0;
    static final int STATE_AVAILABLE = 1;

    /** bluetooth output devices, tried in this order */
    static final int[] BT_OUTS = {OUT_BT_A2DP, OUT_BLE_HEADSET, OUT_BLE_SPEAKER, OUT_BT_SCO};

    /** addresses reported by the app, keyed by internal device type */
    static final HashMap<Integer, String> ADDR = new HashMap<>();

    public static void main(String[] args) {
        String result;
        try {
            if (args.length >= 3 && ("out".equals(args[0]) || "mic".equals(args[0])
                    || "bt".equals(args[0]))) {
                parseAddrs(args[2]);
            }
            if (args.length >= 2 && "out".equals(args[0])) {
                result = switchDevices(new int[]{OUT_USB_HEADSET, OUT_USB_DEVICE},
                        "speaker".equals(args[1]) ? STATE_UNAVAILABLE : STATE_AVAILABLE);
            } else if (args.length >= 2 && "mic".equals(args[0])) {
                result = switchDevices(
                        new int[]{IN_USB_DEVICE, IN_USB_HEADSET, IN_BT_SCO_HEADSET, IN_BLE_HEADSET},
                        "phone".equals(args[1]) ? STATE_UNAVAILABLE : STATE_AVAILABLE);
            } else if (args.length >= 2 && "bt".equals(args[0])) {
                // fake-disconnect BT audio so media falls back to the speaker,
                // or restore the state the BT stack expects
                result = switchDevices(BT_OUTS,
                        "speaker".equals(args[1]) ? STATE_UNAVAILABLE : STATE_AVAILABLE);
            } else if (args.length >= 2 && "force".equals(args[0])) {
                // FOR_MEDIA: 0 none, 1 speaker, 2 headphones
                int cfg = "speaker".equals(args[1]) ? 1 : "headphones".equals(args[1]) ? 2 : 0;
                result = "forceUse(FOR_MEDIA," + cfg + ") rc=" + forceUse(1, cfg);
            } else if (args.length >= 2 && "force-rec".equals(args[0])) {
                int cfg = "speaker".equals(args[1]) ? 1 : 0;
                result = "forceUse(FOR_RECORD," + cfg + ") rc=" + forceUse(2, cfg);
            } else if (args.length >= 1 && "debug".equals(args[0])) {
                result = debugDump();
            } else {
                result = "usage: Helper out speaker|earphone [addrs] | mic phone|earphone [addrs]"
                        + " | bt speaker|restore [addrs]"
                        + " | force speaker|headphones|none | force-rec speaker|none | debug";
            }
        } catch (Throwable t) {
            result = "EXC " + t;
        }
        System.out.println(result);
        System.out.println("USBR_DONE");
        log(result.replace('\n', ' '));
        System.exit(0);
    }

    static void parseAddrs(String s) {
        if (s == null || s.isEmpty()) return;
        if (s.startsWith("b64:")) {
            try {
                s = new String(java.util.Base64.getDecoder().decode(s.substring(4)));
            } catch (Throwable t) {
                logE("bad b64: " + t);
                return;
            }
        }
        log("addrs=" + s);
        for (String entry : s.split("\\|")) {
            int at = entry.indexOf('@');
            if (at <= 0) continue;
            try {
                // the app sends "0xTYPE@addr"; Long.parseLong rejects the 0x
                // prefix, so strip it first (without this every address would
                // be silently dropped and the helper would fall back to probing)
                String hex = entry.substring(0, at).trim();
                if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
                ADDR.put((int) Long.parseLong(hex, 16), entry.substring(at + 1));
            } catch (Throwable ignored) {
            }
        }
    }

    static String switchDevices(int[] devices, int state) {
        StringBuilder sb = new StringBuilder();
        boolean[] done = new boolean[1];
        for (int d : devices) {
            // never touch a type the app did not report
            if (!ADDR.isEmpty() && !ADDR.containsKey(d)) {
                sb.append(name(d)).append(": skip\n");
                continue;
            }
            sb.append(name(d)).append("->").append(state == 1 ? "avail" : "unavail")
              .append(": ").append(switchDevice(d, state, done)).append('\n');
            sleep(150);
        }
        return sb.toString().trim();
    }

    static String switchDevice(int device, int state, boolean[] done) {
        String addr = realAddress(device);
        // the AudioService call keeps its own inventory and the policy manager in
        // sync, so it is always tried first
        String r = serviceSwitch(device, state, addr);
        if (r != null) return r;
        if (state == STATE_AVAILABLE) {
            r = connectViaDeviceInfo(device);
            if (r != null) return r;
            int rc = setDeviceConnectionState(device, STATE_AVAILABLE, addr, name(device));
            if (rc == 0 && isAvailable(device, addr)) {
                return "OK(setState addr=" + (addr.isEmpty() ? "-" : addr) + ")";
            }
            return "FAILED rc=" + rc + " — replug the device to restore";
        }
        int rc = setDeviceConnectionState(device, STATE_UNAVAILABLE, addr, name(device));
        return "rc=" + rc + "(unavail addr=" + (addr.isEmpty() ? "-" : addr) + ")";
    }

    /** AudioManager.setWiredDeviceConnectionState on a system context; root passes the permission. */
    static String serviceSwitch(int device, int state, String addr) {
        try {
            Object am = systemAudioManager();
            if (am == null) return null;
            Method m = AudioManager.class.getMethod("setWiredDeviceConnectionState",
                    int.class, int.class, String.class, String.class);
            m.invoke(am, device, state, addr, name(device));
            sleep(300);
            if (isAvailable(device, addr) == (state == STATE_AVAILABLE)) {
                return "OK(service addr=" + (addr.isEmpty() ? "-" : addr) + ")";
            }
            return null;
        } catch (Throwable t) {
            log("serviceSwitch: " + t);
            return null;
        }
    }

    /**
     * Build attributes from the live AudioDeviceInfo so profiles and descriptors
     * survive the round trip; a bare (type, "", "") pair gets dropped by the
     * policy manager for USB devices.
     */
    static String connectViaDeviceInfo(int deviceType) {
        try {
            Object amObj = systemAudioManager();
            if (amObj == null) return null;
            AudioManager am = (AudioManager) amObj;
            AudioDeviceInfo[] all = am.getDevices(AudioManager.GET_DEVICES_ALL);
            Class<?> ada = Class.forName("android.media.AudioDeviceAttributes");
            Class<?> as = Class.forName("android.media.AudioSystem");
            Method setState = null;
            for (Method m : as.getDeclaredMethods()) {
                if ("setDeviceConnectionState".equals(m.getName())) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 3 && pt[0] == ada && pt[1] == int.class && pt[2] == int.class) {
                        m.setAccessible(true);
                        setState = m;
                        break;
                    }
                }
            }
            if (setState == null) return null;
            Constructor<?> fromInfo = null;
            Constructor<?> full = null;
            try {
                fromInfo = ada.getConstructor(AudioDeviceInfo.class);
            } catch (Throwable ignored) {
            }
            for (Constructor<?> c : ada.getConstructors()) {
                if (c.getParameterTypes().length == 6) {
                    full = c;
                    break;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (AudioDeviceInfo info : all) {
                if (internalType(info) != deviceType) continue;
                Object attrs = null;
                try {
                    if (fromInfo != null) attrs = fromInfo.newInstance(info);
                } catch (Throwable ignored) {
                }
                if (attrs == null && full != null) {
                    Object profiles = info.getClass().getMethod("getAudioProfiles").invoke(info);
                    Object descriptors = info.getClass().getMethod("getAudioDescriptors").invoke(info);
                    attrs = full.newInstance(info.isSource() ? 1 : 0, info.getType(),
                            info.getAddress(), String.valueOf(info.getProductName()),
                            profiles, descriptors);
                }
                if (attrs == null) continue;
                int rc = ((Integer) setState.invoke(null, attrs, STATE_AVAILABLE, 0)).intValue();
                sb.append("info(").append(info.getAddress()).append(") rc=").append(rc);
                if (rc == 0 && isAvailable(deviceType, info.getAddress())) return "OK " + sb;
            }
            return sb.length() > 0 ? "rc!=0 " + sb : null;
        } catch (Throwable t) {
            log("connectViaDeviceInfo: " + t);
            return null;
        }
    }

    /** Try every setDeviceConnectionState signature that ever shipped, newest first. */
    static int setDeviceConnectionState(int device, int state, String address, String name) {
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");
            for (Method m : as.getDeclaredMethods()) {
                if (!"setDeviceConnectionState".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == int.class) {
                    // (int state, Parcel, int codecFormat) — Android 16/17
                    Object attrs = attributes(device, address, name);
                    if (attrs == null) continue;
                    Object port = toPort(attrs);
                    if (port == null) continue;
                    Class<?> pc = Class.forName("android.os.Parcel");
                    Object parcel = pc.getDeclaredMethod("obtain").invoke(null);
                    pc.getMethod("writeToParcel", Class.forName("android.os.Parcelable"), int.class)
                            .invoke(parcel, port, 0);
                    pc.getMethod("setDataPosition", int.class).invoke(parcel, 0);
                    m.setAccessible(true);
                    return ((Integer) m.invoke(null, state, parcel, 0)).intValue();
                }
                if (pt.length == 3 && pt[1] == int.class) {
                    // (AudioDeviceAttributes, int state, int codecFormat) — Android 15+
                    Object attrs = attributes(device, address, name);
                    if (attrs == null) continue;
                    m.setAccessible(true);
                    return ((Integer) m.invoke(null, attrs, state, 0)).intValue();
                }
            }
            // legacy (int device, int state, String address, String name) — up to 14
            Method m = as.getMethod("setDeviceConnectionState",
                    int.class, int.class, String.class, String.class);
            return ((Integer) m.invoke(null, device, state, address, name)).intValue();
        } catch (Throwable t) {
            return -999;
        }
    }

    static Object attributes(int device, String address, String name) {
        try {
            return Class.forName("android.media.AudioDeviceAttributes")
                    .getConstructor(int.class, String.class, String.class)
                    .newInstance(device, address, name);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object toPort(Object attrs) {
        try {
            Class<?> conv = Class.forName("android.media.AidlConversion");
            for (Method m : conv.getDeclaredMethods()) {
                if ("api2aidl_AudioDeviceAttributes_AudioPort".equals(m.getName())) {
                    m.setAccessible(true);
                    return m.invoke(null, attrs);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Internal (native) device type. getInternalType is hidden and missing on
     * some ROMs, so fall back to the fixed mapping for USB audio classes.
     */
    static int internalType(AudioDeviceInfo d) {
        try {
            return ((Integer) AudioDeviceInfo.class.getMethod("getInternalType").invoke(d)).intValue();
        } catch (Throwable t) {
        int pub = d.getType();
        boolean in = d.isSource();
        switch (pub) {
            case 12: return in ? 0x80000800 : 0x2000;    // TYPE_USB_ACCESSORY
            case 13: return in ? 0x80001000 : 0x4000;    // TYPE_USB_DEVICE
            case 22: return in ? 0x82000000 : 0x4000000; // TYPE_USB_HEADSET
            case 7: return in ? 0x80000008 : 0x10;       // TYPE_BLUETOOTH_SCO
            case 8: return 0x80;                         // TYPE_BLUETOOTH_A2DP
            case 26: return in ? 0x90000000 : 0x10000000; // TYPE_BLE_HEADSET
            case 27: return 0x20000000;                  // TYPE_BLE_SPEAKER
            case 28: return 0x40000000;                  // TYPE_BLE_BROADCAST
            default: return in ? (0x80000000 | pub) : pub;
        }
        }
    }

    /** app snapshot, then AudioService, then the ALSA cards */
    static String realAddress(int deviceType) {
        String a = ADDR.get(deviceType);
        if (a != null && !a.isEmpty()) return a;
        a = probeAudioService(deviceType);
        if (a != null && !a.isEmpty()) return a;
        return probeAlsa(deviceType);
    }

    static String probeAudioService(int deviceType) {
        try {
            Object amObj = systemAudioManager();
            if (amObj == null) return "";
            AudioDeviceInfo[] all = ((AudioManager) amObj).getDevices(AudioManager.GET_DEVICES_ALL);
            for (AudioDeviceInfo info : all) {
                if (internalType(info) == deviceType
                        && info.getAddress() != null && !info.getAddress().isEmpty()) {
                    return info.getAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** USB audio cards still show up here after the framework dropped them. */
    static String probeAlsa(int deviceType) {
        try {
            for (String l : readAll(new java.io.File("/proc/asound/cards")).split("\n")) {
                if (!l.toLowerCase().contains("usb")) continue;
                java.util.regex.Matcher cm = java.util.regex.Pattern
                        .compile("^\\s*(\\d+)\\s+\\[").matcher(l);
                if (!cm.find()) continue;
                int card = Integer.parseInt(cm.group(1));
                int pcm = 0;
                java.io.File[] entries = new java.io.File("/proc/asound/card" + card).listFiles();
                if (entries != null) {
                    for (java.io.File e : entries) {
                        if (e.getName().matches("pcm\\d+c")) {
                            pcm = Integer.parseInt(e.getName().replaceAll("[^0-9]", ""));
                            break;
                        }
                    }
                }
                return "card=" + card + ";device=" + pcm;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    static Object systemAudioManager() {
        try {
            if (sAm != null) return sAm;
            if (!sLooperReady) {
                Looper.prepareMainLooper();
                sLooperReady = true;
            }
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object sysMain = at.getMethod("systemMain").invoke(null);
            Context ctx = (Context) sysMain.getClass().getMethod("getSystemContext").invoke(sysMain);
            sAm = ctx.getSystemService(Context.AUDIO_SERVICE);
            return sAm;
        } catch (Throwable t) {
            return null;
        }
    }

    static Object sAm;
    static boolean sLooperReady;

    static boolean isAvailable(int device, String addr) {
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");
            Method m = as.getMethod("getDeviceConnectionState", int.class, String.class);
            return ((Integer) m.invoke(null, device, addr)).intValue() == STATE_AVAILABLE;
        } catch (Throwable t) {
            return false;
        }
    }

    static int forceUse(int usage, int config) {
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");
            Method m = as.getMethod("setForceUse", int.class, int.class);
            return ((Integer) m.invoke(null, usage, config)).intValue();
        } catch (Throwable t) {
            return -999;
        }
    }

    static String debugDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("-- policy manager --\n");
        int[] all = {OUT_USB_DEVICE, OUT_USB_HEADSET, IN_BUILTIN_MIC, IN_USB_DEVICE,
                IN_USB_HEADSET, OUT_BT_A2DP, OUT_BT_SCO, OUT_BLE_HEADSET,
                IN_BT_SCO_HEADSET, IN_BLE_HEADSET};
        for (int d : all) {
            String addr = realAddress(d);
            sb.append(name(d)).append("(0x").append(Integer.toHexString(d)).append(") addr=")
              .append(addr.isEmpty() ? "-" : addr).append(" state=");
            try {
                Class<?> as = Class.forName("android.media.AudioSystem");
                Method m = as.getMethod("getDeviceConnectionState", int.class, String.class);
                sb.append(((Integer) m.invoke(null, d, addr)).intValue());
            } catch (Throwable t) {
                sb.append("ERR ").append(t.getClass().getSimpleName());
            }
            sb.append('\n');
        }
        sb.append("-- AudioService --\n");
        try {
            Object amObj = systemAudioManager();
            if (amObj != null) {
                AudioDeviceInfo[] devs = ((AudioManager) amObj).getDevices(AudioManager.GET_DEVICES_ALL);
                for (AudioDeviceInfo d : devs) {
                    sb.append("0x").append(Integer.toHexString(internalType(d)))
                      .append(" addr=").append(d.getAddress())
                      .append(" name=").append(d.getProductName())
                      .append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("list ERR ").append(t).append('\n');
        }
        return sb.toString().trim();
    }

    static String name(int d) {
        switch (d) {
            case OUT_USB_DEVICE: return "OUT_USB_DEVICE";
            case OUT_USB_HEADSET: return "OUT_USB_HEADSET";
            case IN_BUILTIN_MIC: return "IN_BUILTIN_MIC";
            case IN_USB_DEVICE: return "IN_USB_DEVICE";
            case IN_USB_HEADSET: return "IN_USB_HEADSET";
            case OUT_BT_A2DP: return "OUT_BT_A2DP";
            case OUT_BT_SCO: return "OUT_BT_SCO";
            case OUT_BLE_HEADSET: return "OUT_BLE_HEADSET";
            case OUT_BLE_SPEAKER: return "OUT_BLE_SPEAKER";
            case IN_BT_SCO_HEADSET: return "IN_BT_SCO_HEADSET";
            case IN_BLE_HEADSET: return "IN_BLE_HEADSET";
            default: return "0x" + Integer.toHexString(d);
        }
    }

    static String readAll(java.io.File f) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    static void log(String s) {
        try {
            android.util.Log.i("usbroute", s);
        } catch (Throwable ignored) {
        }
    }

    static void logE(String s) {
        try {
            android.util.Log.e("usbroute", s);
        } catch (Throwable ignored) {
        }
    }
}
