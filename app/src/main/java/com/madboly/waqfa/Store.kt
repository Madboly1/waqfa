package com.madboly.waqfa

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("waqfa", Context.MODE_PRIVATE)
    private fun s(k: String, d: String = "") = p.getString(k, d) ?: d
    private fun ps(k: String, v: String) { p.edit().putString(k, v).apply() }
    private fun i(k: String, d: Int) = p.getInt(k, d)
    private fun pi(k: String, v: Int) { p.edit().putInt(k, v).apply() }
    private fun l(k: String, d: Long = 0L) = p.getLong(k, d)
    private fun pl(k: String, v: Long) { p.edit().putLong(k, v).apply() }
    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun pb(k: String, v: Boolean) { p.edit().putBoolean(k, v).apply() }

    var enabled: Boolean get() = b("enabled", false); set(v) = pb("enabled", v)
    var targetMin: Int get() = i("target_min", 15); set(v) = pi("target_min", v.coerceIn(1, 1440))
    var accMs: Long get() = l("acc_ms"); set(v) = pl("acc_ms", v)
    var page: Int get() = i("page", 2); set(v) = pi("page", if (v > 604 || v < 1) 1 else v)
    var snoozeMin: Int get() = i("snooze_min", 5); set(v) = pi("snooze_min", v.coerceIn(1, 120))
    var pauseDay: String get() = s("pause_day"); set(v) = ps("pause_day", v)
    var lastTick: Long get() = l("last_tick"); set(v) = pl("last_tick", v)
    var lastErr: String get() = s("last_err"); set(v) = ps("last_err", v)
    var onboarded: Boolean get() = b("onboarded", false); set(v) = pb("onboarded", v)

    // prayer
    var prayerOn: Boolean get() = b("prayer_on", true); set(v) = pb("prayer_on", v)
    var city: String get() = s("city", "Cairo"); set(v) = ps("city", v)
    var country: String get() = s("country", "Egypt"); set(v) = ps("country", v)
    var pBefore: Int get() = i("p_before", 10); set(v) = pi("p_before", v.coerceIn(0, 120))
    var pAfter: Int get() = i("p_after", 20); set(v) = pi("p_after", v.coerceIn(0, 120))
    var prayerData: String get() = s("prayer_data"); set(v) = ps("prayer_data", v)
    var prayerTry: Long get() = l("prayer_try"); set(v) = pl("prayer_try", v)
    var prayerMsg: String get() = s("prayer_msg"); set(v) = ps("prayer_msg", v)
    var holdMsg: String get() = s("hold_msg"); set(v) = ps("hold_msg", v)

    // stats
    var daysCsv: String get() = s("days"); set(v) = ps("days", v)
    var totalPages: Int get() = i("total", 0); set(v) = pi("total", v)
    var laterCount: Int get() = i("later", 0); set(v) = pi("later", v)
    var sumMs: Long get() = l("sum_ms"); set(v) = pl("sum_ms", v)
    var doneCount: Int get() = i("done_cnt", 0); set(v) = pi("done_cnt", v)

    // backup / account
    var refreshToken: String get() = s("refresh"); set(v) = ps("refresh", v)
    var email: String get() = s("email"); set(v) = ps("email", v)
    var autoBackup: Boolean get() = b("auto_backup", true); set(v) = pb("auto_backup", v)
    var lastBackup: Long get() = l("last_backup"); set(v) = pl("last_backup", v)
    var backupMsg: String get() = s("backup_msg"); set(v) = ps("backup_msg", v)
    var lastBackupTry: Long get() = l("last_backup_try"); set(v) = pl("last_backup_try", v)
    var pkce: String get() = s("pkce"); set(v) = ps("pkce", v)

    private fun fmt(c: Calendar) = SimpleDateFormat("yyyyMMdd", Locale.US).format(c.time)
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    fun pausedToday() = pauseDay == today()
    fun setPausedToday(on: Boolean) { pauseDay = if (on) today() else "" }

    private fun dayMap(): MutableMap<String, Int> {
        val m = LinkedHashMap<String, Int>()
        for (e in daysCsv.split(",")) { val kv = e.split(":"); if (kv.size == 2) kv[1].toIntOrNull()?.let { m[kv[0]] = it } }
        return m
    }
    fun recordDone(accMsAtDone: Long) {
        val m = dayMap(); val t = today(); m[t] = (m[t] ?: 0) + 1
        val keep = m.entries.sortedBy { it.key }.takeLast(60)
        daysCsv = keep.joinToString(",") { it.key + ":" + it.value }
        totalPages = totalPages + 1; doneCount = doneCount + 1; sumMs = sumMs + accMsAtDone
    }
    fun pagesOn(c: Calendar): Int = dayMap()[fmt(c)] ?: 0
    fun streak(): Int {
        val m = dayMap(); val c = Calendar.getInstance()
        if ((m[fmt(c)] ?: 0) == 0) c.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0
        while ((m[fmt(c)] ?: 0) > 0) { n++; c.add(Calendar.DAY_OF_YEAR, -1) }
        return n
    }

    fun exportJson(): String {
        val o = JSONObject()
        o.put("v", 1); o.put("target_min", targetMin); o.put("page", page); o.put("snooze_min", snoozeMin)
        o.put("days", daysCsv); o.put("total", totalPages); o.put("later", laterCount); o.put("sum_ms", sumMs); o.put("done_cnt", doneCount)
        o.put("city", city); o.put("country", country); o.put("p_before", pBefore); o.put("p_after", pAfter); o.put("prayer_on", prayerOn)
        o.put("auto_backup", autoBackup)
        return o.toString()
    }
    fun importJson(t: String) {
        val o = JSONObject(t)
        if (o.has("target_min")) targetMin = o.getInt("target_min")
        if (o.has("page")) page = o.getInt("page")
        if (o.has("snooze_min")) snoozeMin = o.getInt("snooze_min")
        if (o.has("days")) daysCsv = o.getString("days")
        if (o.has("total")) totalPages = o.getInt("total")
        if (o.has("later")) laterCount = o.getInt("later")
        if (o.has("sum_ms")) sumMs = o.getLong("sum_ms")
        if (o.has("done_cnt")) doneCount = o.getInt("done_cnt")
        if (o.has("city")) city = o.getString("city")
        if (o.has("country")) country = o.getString("country")
        if (o.has("p_before")) pBefore = o.getInt("p_before")
        if (o.has("p_after")) pAfter = o.getInt("p_after")
        if (o.has("prayer_on")) prayerOn = o.getBoolean("prayer_on")
        if (o.has("auto_backup")) autoBackup = o.getBoolean("auto_backup")
        prayerData = ""; prayerTry = 0L
    }
}
