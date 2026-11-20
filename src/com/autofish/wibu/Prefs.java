package com.autofish.wibu;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Settings storage + a small in-memory log shown in the app. */
final class Prefs {
    private Prefs() {
    }

    static Bot.Config load(Context c) {
        SharedPreferences p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        Bot.Config k = new Bot.Config();
        k.castDelay = p.getFloat("castDelay", k.castDelay);
        k.maxWait = p.getFloat("maxWait", k.maxWait);
        k.maxCasts = p.getInt("maxCasts", k.maxCasts);
        k.latency = p.getFloat("latency", k.latency);
        return k;
    }

    static void save(Context c, Bot.Config k) {
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putFloat("castDelay", k.castDelay)
                .putFloat("maxWait", k.maxWait)
                .putInt("maxCasts", k.maxCasts)
                .putFloat("latency", k.latency)
                .apply();
    }

    // ---------------------------------------------------------------- log
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    static synchronized void log(String msg) {
        LOG.addFirst(FMT.format(new Date()) + "  " + msg);
        while (LOG.size() > 40) LOG.removeLast();
        android.util.Log.i("AutoMancing", msg);
        Riwayat.event(msg);
    }

    static synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (String s : LOG) sb.append(s).append('\n');
        return sb.toString();
    }
}
