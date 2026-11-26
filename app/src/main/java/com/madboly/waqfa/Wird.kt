package com.madboly.waqfa

import android.content.Context
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Wird {
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun shownToday(st: Store): Int = if (st.shownDay == today()) st.shownCount else 0
    fun markShown(st: Store) { val t = today(); if (st.shownDay != t) { st.shownDay = t; st.shownCount = 0 }; st.shownCount = st.shownCount + 1 }

    fun laterAcc(st: Store): Long = (st.targetMin - st.snoozeMin) * 60000L

    fun applyDone(st: Store, book: Library.Book?, page: Int) {
        st.recordDone(st.accMs)
        if (book != null) { Library.advance(st, book.id); st.bookPages = st.bookPages + 1 } else st.page = page + 1
        if (st.mode == "alt" && Library.current(st) != null) st.altTurn = 1 - st.altTurn
        st.accMs = 0L
    }

    fun applyLater(st: Store) { st.laterCount = st.laterCount + 1; st.accMs = laterAcc(st) }

    /** reason text when a popup should wait, or null */
    fun smartHold(c: Context, st: Store): String? {
        if (st.quietOn) {
            val cal = Calendar.getInstance(); val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val a = st.quietFrom; val b = st.quietTo
            val inQuiet = if (a == b) false else if (a < b) now in a until b else (now >= a || now < b)
            if (inQuiet) return "ساعات الهدوء (" + st.quietFrom / 60 + ":00 إلى " + st.quietTo / 60 + ":00)"
        }
        if (st.maxPerDay > 0 && shownToday(st) >= st.maxPerDay) return "وصلت الحد اليومي (" + st.maxPerDay + " نافذة)"
        if (st.smartVideo) {
            try {
                val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val d = android.graphics.Point()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(d)
                if (d.x > d.y) return "مؤجّل: الشاشة بالعرض (فيديو أو لعبة)"
            } catch (_: Throwable) {}
        }
        return null
    }
}
