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

    private val SURAH = arrayOf("الفاتحة","البقرة","آل عمران","النساء","المائدة","الأنعام","الأعراف","الأنفال","التوبة","يونس","هود","يوسف","الرعد","إبراهيم","الحجر","النحل","الإسراء","الكهف","مريم","طه","الأنبياء","الحج","المؤمنون","النور","الفرقان","الشعراء","النمل","القصص","العنكبوت","الروم","لقمان","السجدة","الأحزاب","سبأ","فاطر","يس","الصافات","ص","الزمر","غافر","فصلت","الشورى","الزخرف","الدخان","الجاثية","الأحقاف","محمد","الفتح","الحجرات","ق","الذاريات","الطور","النجم","القمر","الرحمن","الواقعة","الحديد","المجادلة","الحشر","الممتحنة","الصف","الجمعة","المنافقون","التغابن","الطلاق","التحريم","الملك","القلم","الحاقة","المعارج","نوح","الجن","المزمل","المدثر","القيامة","الإنسان","المرسلات","النبأ","النازعات","عبس","التكوير","الانفطار","المطففين","الانشقاق","البروج","الطارق","الأعلى","الغاشية","الفجر","البلد","الشمس","الليل","الضحى","الشرح","التين","العلق","القدر","البينة","الزلزلة","العاديات","القارعة","التكاثر","العصر","الهمزة","الفيل","قريش","الماعون","الكوثر","الكافرون","النصر","المسد","الإخلاص","الفلق","الناس")
    private val START = intArrayOf(1,2,50,77,106,128,151,177,187,208,221,235,249,255,262,267,282,293,305,312,322,332,342,350,359,367,377,385,396,404,411,415,418,428,434,440,446,453,458,467,477,483,489,496,499,502,507,511,515,518,520,523,526,528,531,534,537,542,545,549,551,553,554,556,558,560,562,564,566,568,570,572,574,575,577,578,580,582,583,585,586,587,587,589,590,591,591,592,593,594,595,595,596,596,597,597,598,598,599,599,600,600,601,601,601,602,602,602,603,603,603,604,604,604)
    /** name of the last surah that starts on or before this page ("" if tables disagree) */
    fun surahName(page: Int): String {
        if (SURAH.size != START.size) return ""
        var r = 0
        for (i in START.indices) if (START[i] <= page) r = i
        return "سورة " + SURAH[r]
    }
}
