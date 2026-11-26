package com.madboly.waqfa

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object Prayer {
    @Volatile private var fetching = false
    private val names = arrayOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    private fun key(st: Store) = st.city.trim() + "|" + st.country.trim()
    private fun dstr(c: Calendar) = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(c.time)

    private fun todayMins(st: Store, c: Calendar): IntArray? {
        val k = key(st) + ";" + dstr(c) + ";"
        for (line in st.prayerData.split("\n")) if (line.startsWith(k)) {
            val parts = line.substring(k.length).split(",")
            if (parts.size == 5) return IntArray(5) { parts[it].toIntOrNull() ?: return null }
        }
        return null
    }
    private fun hasDate(st: Store, c: Calendar) = todayMins(st, c) != null

    fun loadedToday(st: Store): Boolean = hasDate(st, Calendar.getInstance())

    fun blockedUntil(st: Store): String? {
        if (!st.prayerOn) return null
        val c = Calendar.getInstance()
        val m = todayMins(st, c) ?: return null
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        for (p in m) if (now >= p - st.pBefore && now < p + st.pAfter) {
            val e = p + st.pAfter
            return String.format(Locale.US, "%d:%02d", (e / 60) % 24, e % 60)
        }
        return null
    }

    fun ensure(st: Store) {
        if (!st.prayerOn || fetching) return
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance()
        val c3 = Calendar.getInstance(); c3.add(Calendar.DAY_OF_YEAR, 3)
        if (hasDate(st, c) && hasDate(st, c3)) return
        if (now - st.prayerTry < 10 * 60 * 1000L) return
        st.prayerTry = now
        fetching = true
        Thread {
            try {
                val ok1 = fetchMonth(st, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
                if (ok1 && (c3.get(Calendar.MONTH) != c.get(Calendar.MONTH))) fetchMonth(st, c3.get(Calendar.YEAR), c3.get(Calendar.MONTH) + 1)
            } finally { fetching = false }
        }.start()
    }

    fun ensureSync(st: Store) {
        val c = Calendar.getInstance()
        val c3 = Calendar.getInstance(); c3.add(Calendar.DAY_OF_YEAR, 7)
        if (hasDate(st, c) && hasDate(st, c3)) return
        fetchNow(st)
    }

    fun fetchNow(st: Store): Boolean {
        val c = Calendar.getInstance()
        val ok = fetchMonth(st, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        val c3 = Calendar.getInstance(); c3.add(Calendar.DAY_OF_YEAR, 4)
        if (ok && c3.get(Calendar.MONTH) != c.get(Calendar.MONTH)) fetchMonth(st, c3.get(Calendar.YEAR), c3.get(Calendar.MONTH) + 1)
        return ok
    }

    private fun fetchMonth(st: Store, year: Int, month: Int): Boolean {
        try {
            val u = "https://api.aladhan.com/v1/calendarByCity/$year/$month?city=" + URLEncoder.encode(st.city.trim(), "UTF-8") +
                "&country=" + URLEncoder.encode(st.country.trim(), "UTF-8") + "&method=5"
            val con = URL(u).openConnection() as HttpURLConnection
            con.connectTimeout = 15000; con.readTimeout = 20000
            val code = con.responseCode
            if (code != 200) { st.prayerMsg = "خطأ $code من خدمة المواقيت"; return false }
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).getJSONArray("data")
            val k = key(st)
            val keep = ArrayList<String>()
            val mm = String.format(Locale.US, "%02d-%d", month, year)
            for (line in st.prayerData.split("\n")) if (line.startsWith("$k;") && line.split(";").getOrNull(1)?.substring(3) != mm) keep.add(line)
            for (n in 0 until arr.length()) {
                val o = arr.getJSONObject(n)
                val date = o.getJSONObject("date").getJSONObject("gregorian").getString("date")
                val t = o.getJSONObject("timings")
                val mins = names.map { nm ->
                    val v = t.getString(nm).trim().substring(0, 5)
                    v.substring(0, 2).toInt() * 60 + v.substring(3, 5).toInt()
                }
                keep.add("$k;$date;" + mins.joinToString(","))
            }
            st.prayerData = keep.takeLast(70).joinToString("\n")
            st.prayerMsg = "تم تحديث المواقيت: " + st.city
            return true
        } catch (e: Throwable) {
            st.prayerMsg = "تعذر تحميل المواقيت (" + e.javaClass.simpleName + ")"
            return false
        }
    }
}
