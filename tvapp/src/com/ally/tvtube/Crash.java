package com.ally.tvtube;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Ghi lai loi khong bat duoc ra tep de nguoi dung gui lai. */
public final class Crash {

    private static File logFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, "loi.txt");
    }

    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    File f = logFile(ctx);
                    FileOutputStream fos = new FileOutputStream(f, true);
                    PrintWriter w = new PrintWriter(fos);
                    w.println("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                    w.println("Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                            + " / " + Build.MANUFACTURER + " " + Build.MODEL);
                    w.println("Luồng: " + t.getName());
                    e.printStackTrace(w);
                    w.println();
                    w.flush();
                    w.close();
                    fos.close();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    /** Duong dan tep loi de hien thi cho nguoi dung biet cho gui. */
    public static String path(Context c) {
        return logFile(c).getAbsolutePath();
    }
}
