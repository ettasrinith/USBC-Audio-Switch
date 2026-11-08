package com.lasm.usb;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Shells out to su and runs Helper through app_process as root.
 *
 * Root etiquette: su is probed once — that is the moment Magisk / KernelSU /
 * APatch show their grant dialog — the working binary is cached for later
 * calls, and every step is reported through Cb so the UI can show it.
 *
 * Nothing is hardcoded: the package name and the result-file path are taken
 * from the running app via {@link #init(Context)}.
 */
public final class Root {

    public interface Cb {
        void onResult(String output);
    }

    public static final int UNKNOWN = 0;
    public static final int CHECKING = 1;
    public static final int GRANTED = 2;
    public static final int DENIED = 3;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    /** where the su binary hides per root manager; probed at runtime, never assumed */
    private static final String[] SU_PATHS = {
            "su",
            "/system/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/sbin/su",
            "/system/xbin/su",
            "/vendor/bin/su",
            "/system/sd/xbin/su",
    };

    private static volatile int sState = UNKNOWN;
    private static volatile String sSu;
    private static volatile String sPkg = "com.lasm.usb";
    private static volatile String sOutFile =
            "/storage/emulated/0/Android/data/com.lasm.usb/files/usbroute.out";

    private Root() {
    }

    public static int state() {
        return sState;
    }

    public static String suPath() {
        return sSu != null ? sSu : "su not detected yet";
    }

    /** Package and output path come from the running app, not from constants. */
    public static void init(Context ctx) {
        try {
            sPkg = ctx.getPackageName();
            File d = ctx.getExternalFilesDir(null);
            if (d != null) {
                d.mkdirs();
                sOutFile = new File(d, "usbroute.out").getAbsolutePath();
            }
        } catch (Throwable t) {
            AppLog.e("root", "init: " + t);
        }
    }

    /**
     * Ask for superuser right away — this is what pops the manager's grant
     * dialog. Call it when the app opens so root is settled before the user
     * taps anything.
     */
    public static void check(final Cb cb) {
        sState = CHECKING;
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                cb.onResult("requesting superuser access…");
                if (!probe(cb)) {
                    sState = DENIED;
                    cb.onResult("root not granted — approve this app in Magisk / KernelSU / APatch, "
                            + "then tap the root chip to retry");
                }
            }
        });
    }

    public static void exec(final String helperArgs, final Cb cb) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                runSync(helperArgs, cb);
            }
        });
    }

    private static void runSync(String helperArgs, Cb cb) {
        if (sState != GRANTED) {
            cb.onResult("requesting superuser access…");
            if (!probe(cb)) {
                sState = DENIED;
                cb.onResult("ROOT_FAIL — grant root for this app in your su manager and retry");
                return;
            }
        }
        cb.onResult("$ Helper " + helperArgs);
        // app_process stdout does not always survive the su pipe, so the helper
        // writes into our external files dir and we read it back afterwards
        String inner = "CP=$(pm path " + sPkg + " 2>/dev/null | sed -n s/package://p | head -n 1); "
                + "[ -n \"$CP\" ] && CLASSPATH=$CP app_process / --nice-name=usbroute "
                + sPkg + ".Helper " + helperArgs + " > " + shq(sOutFile) + " 2>&1; echo USBR_EXIT:$?";
        String out = runSh(su() + " -c " + shq(inner), 30000);
        if (out != null && out.contains("USBR_EXIT:0")) {
            String result = readFile(sOutFile);
            if (result != null && result.contains("USBR_DONE")) {
                cb.onResult(result.replace("USBR_DONE", "").trim());
                return;
            }
            cb.onResult("helper exited 0 but wrote no result\n" + first(result));
            return;
        }
        cb.onResult("helper run failed: " + first(out));
        cb.onResult("ROOT_FAIL — grant root for this app in your su manager and retry");
    }

    /** Try the cached su first, then every known location; caches the winner. */
    private static boolean probe(Cb cb) {
        sState = CHECKING;
        List<String> order = new ArrayList<>();
        if (sSu != null) order.add(sSu);
        for (String p : SU_PATHS) {
            if (!order.contains(p)) order.add(p);
        }
        for (String su : order) {
            String out = runSh(su + " -c id", 60000);
            if (out != null && out.contains("uid=0")) {
                sSu = su;
                sState = GRANTED;
                cb.onResult("root granted via " + su);
                return true;
            }
        }
        sSu = null;
        return false;
    }

    private static String su() {
        return sSu != null ? sSu : "su";
    }

    private static String first(String s) {
        if (s == null) return "(no output)";
        s = s.trim();
        if (s.isEmpty()) return "(empty)";
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, lines.length); i++) sb.append(lines[i]).append('\n');
        return sb.toString().trim();
    }

    private static String readFile(String path) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(path)));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String runSh(String cmdline, long timeoutMs) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmdline});
            final StringBuilder sb = new StringBuilder();
            Thread t1 = pump(p.getInputStream(), sb);
            Thread t2 = pump(p.getErrorStream(), sb);
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                synchronized (sb) {
                    sb.append("(timed out — waiting for the root prompt)");
                }
            }
            t1.join(1000);
            t2.join(1000);
            p.destroy();
            return sb.toString();
        } catch (Throwable t) {
            return "EXC " + t;
        }
    }

    private static Thread pump(final java.io.InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(in));
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (sb) {
                            sb.append(line).append('\n');
                        }
                    }
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
