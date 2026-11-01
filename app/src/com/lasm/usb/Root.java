package com.lasm.usb;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Shells out to su and runs Helper through app_process as root. */
public final class Root {

    public interface Cb {
        void onResult(String output);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final String PKG = "com.lasm.usb";
    private static final String OUT_FILE =
            "/storage/emulated/0/Android/data/" + PKG + "/files/usbroute.out";

    private Root() {
    }

    public static void exec(final String helperArgs, final Cb cb) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                cb.onResult("$ Helper " + helperArgs);
                runSync(helperArgs, cb);
            }
        });
    }

    private static void runSync(String helperArgs, Cb cb) {
        // app_process stdout does not always survive the su pipe, so the helper
        // writes into our external files dir and we read it back afterwards
        String inner = "CP=$(pm path " + PKG + " 2>/dev/null | sed -n s/package://p | head -n 1); "
                + "[ -n \"$CP\" ] && CLASSPATH=$CP app_process / --nice-name=usbroute "
                + PKG + ".Helper " + helperArgs + " > " + OUT_FILE + " 2>&1; echo USBR_EXIT:$?";
        String[] attempts = {
                "su -c " + shq(inner),
                "/system/bin/su -c " + shq(inner),
                "/data/adb/ksu/bin/su -c " + shq(inner),
                "/data/adb/ap/bin/su -c " + shq(inner),
                "/sbin/su -c " + shq(inner),
                "/system/xbin/su -c " + shq(inner),
        };
        for (int i = 0; i < attempts.length; i++) {
            String bin = attempts[i].substring(0, attempts[i].indexOf(' '));
            cb.onResult("su " + (i + 1) + "/" + attempts.length + " via " + bin);
            String out = runSh(attempts[i], 30000);
            if (out != null && out.contains("USBR_EXIT:0")) {
                String result = readFile(OUT_FILE);
                if (result != null && result.contains("USBR_DONE")) {
                    cb.onResult(result.replace("USBR_DONE", "").trim());
                    return;
                }
                cb.onResult("helper exited 0 but no result file\n" + first(result));
                return;
            }
            cb.onResult("attempt " + (i + 1) + ": " + first(out));
        }
        cb.onResult("ROOT_FAIL — grant root for this app in your su manager and retry");
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
                    new java.io.FileInputStream(path)));
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
                sb.append("(timed out — approve the root prompt)");
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

    public static void ensureOutDir(android.content.Context ctx) {
        try {
            java.io.File d = ctx.getExternalFilesDir(null);
            if (d != null) d.mkdirs();
        } catch (Throwable ignored) {
        }
    }
}
