package com.lasm.usb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Central log: every line is timestamped, mirrored to logcat, kept in a ring
 * buffer for the UI and appended to a plain-text file next to the helper
 * output, so logs survive process death and can be shared for bug reports.
 */
public final class AppLog {

    public interface Listener {
        void onLine(String line);
    }

    private static final int CAP = 800;
    private static final ArrayDeque<String> BUF = new ArrayDeque<>();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private static volatile File sFile;
    private static volatile Listener sListener;

    private AppLog() {
    }

    public static void init(Context ctx) {
        try {
            File d = ctx.getExternalFilesDir(null);
            if (d != null) {
                d.mkdirs();
                sFile = new File(d, "usbroute-log.txt");
            }
        } catch (Throwable ignored) {
        }
    }

    public static void i(String tag, String msg) {
        log('I', tag, msg);
    }

    public static void w(String tag, String msg) {
        log('W', tag, msg);
    }

    public static void e(String tag, String msg) {
        log('E', tag, msg);
    }

    private static void log(char lvl, String tag, String msg) {
        final String line = TS.format(new Date()) + ' ' + lvl + '/' + tag + "  " + msg;
        synchronized (BUF) {
            BUF.addLast(line);
            while (BUF.size() > CAP) BUF.pollFirst();
        }
        try {
            if (lvl == 'E') android.util.Log.e("usbroute", msg);
            else if (lvl == 'W') android.util.Log.w("usbroute", msg);
            else android.util.Log.i("usbroute", msg);
        } catch (Throwable ignored) {
        }
        appendFile(line);
        final Listener l = sListener;
        if (l != null) {
            UI.post(new Runnable() {
                @Override
                public void run() {
                    l.onLine(line);
                }
            });
        }
    }

    private static void appendFile(String line) {
        File f = sFile;
        if (f == null) return;
        try {
            Writer w = new BufferedWriter(new FileWriter(f, true));
            w.write(line);
            w.write('\n');
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** Attach the UI listener; replays the buffer so logs survive recreate/rotate. */
    public static void attach(Listener l) {
        sListener = l;
        if (l == null) return;
        final String all = dump();
        if (all.length() > 0) {
            UI.post(new Runnable() {
                @Override
                public void run() {
                    Listener cur = sListener;
                    if (cur != null) cur.onLine(all);
                }
            });
        }
    }

    public static void detach() {
        sListener = null;
    }

    private static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (BUF) {
            for (String s : BUF) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (BUF) {
            BUF.clear();
        }
        File f = sFile;
        if (f != null) {
            try {
                new FileWriter(f, false).close();
            } catch (Throwable ignored) {
            }
        }
        i("app", "log cleared");
    }

    /** Share the current buffer as plain text. */
    public static void share(Activity a) {
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("text/plain");
        it.putExtra(Intent.EXTRA_SUBJECT, "USB Audio Router log");
        it.putExtra(Intent.EXTRA_TEXT,
                "USB Audio Router log — " + Build.MODEL + " (API " + Build.VERSION.SDK_INT + ")\n\n"
                        + dump());
        a.startActivity(Intent.createChooser(it, "Share log"));
    }
}
