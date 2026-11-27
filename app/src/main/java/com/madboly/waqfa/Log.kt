package com.madboly.waqfa

import android.content.Context
import java.io.File

/** Diagnostics were removed at the user's request. Calls are kept as no-ops; the old log file is deleted. */
object Log {
    fun i(c: Context, msg: String) {}
    fun purge(c: Context) { try { File(c.filesDir, "log.txt").delete() } catch (_: Throwable) {} }
}
