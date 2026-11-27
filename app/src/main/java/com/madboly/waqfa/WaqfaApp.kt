package com.madboly.waqfa

import android.app.Application
import java.io.File

class WaqfaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.purge(this)
        try {
            val st = Store(this)
            val gap = if (st.lastTick > 0) (System.currentTimeMillis() - st.lastTick) / 60000 else -1
            Log.i(this, "process started on " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " android " + android.os.Build.VERSION.SDK_INT + ", service last alive " + (if (gap < 0) "never" else "$gap min ago") + ", enabled=" + st.enabled + ", acc=" + st.accMs / 1000 + "s")
        } catch (_: Throwable) {}
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                File(filesDir, "crash.txt").writeText("thread=" + t.name + "\n" + sw.toString().take(6000))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
