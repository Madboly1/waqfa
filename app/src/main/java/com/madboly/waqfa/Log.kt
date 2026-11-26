package com.madboly.waqfa

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Log {
    @Synchronized fun i(c: Context, msg: String) {
        try {
            val f = File(c.applicationContext.filesDir, "log.txt")
            val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " " + msg
            val old = if (f.exists()) f.readLines() else emptyList()
            f.writeText((old + line).takeLast(80).joinToString("\n"))
        } catch (_: Throwable) {}
    }
    fun read(c: Context): String = try {
        val f = File(c.filesDir, "log.txt"); if (f.exists()) f.readLines().reversed().joinToString("\n") else "(فارغ)"
    } catch (_: Throwable) { "(تعذر القراءة)" }
    fun clear(c: Context) { try { File(c.filesDir, "log.txt").delete() } catch (_: Throwable) {} }
}
