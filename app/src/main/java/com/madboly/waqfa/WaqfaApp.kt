package com.madboly.waqfa

import android.app.Application
import java.io.File

class WaqfaApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
