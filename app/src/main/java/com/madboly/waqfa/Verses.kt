package com.madboly.waqfa

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.ScrollView
import android.widget.TextView

/** Verse-by-verse mode. Text: Tanzil Quran text (Uthmani), unmodified, shown in the Amiri Quran font. */
object Verses {
    class V(val sura: Int, val aya: Int, val text: String)

    @Volatile private var all: List<V>? = null
    @Volatile private var starts: IntArray? = null
    @Volatile private var face: Typeface? = null

    fun load(ctx: Context): List<V> {
        all?.let { return it }
        val out = ArrayList<V>(6240)
        try {
            ctx.applicationContext.assets.open("quran-uthmani.txt").bufferedReader(Charsets.UTF_8).useLines { seq ->
                for (l in seq) {
                    if (l.isBlank() || l.startsWith("#")) continue
                    val p = l.split('|', limit = 3); if (p.size < 3) continue
                    out.add(V(p[0].toInt(), p[1].toInt(), p[2]))
                }
            }
        } catch (_: Throwable) {}
        all = out; return out
    }

    private fun pageStarts(ctx: Context): IntArray {
        starts?.let { return it }
        val a = try { ctx.applicationContext.assets.open("page_starts.txt").bufferedReader().readText().split("\n").mapNotNull { it.trim().toIntOrNull() }.toIntArray() } catch (_: Throwable) { IntArray(0) }
        starts = a; return a
    }

    fun startOf(ctx: Context, page: Int): Int { val s = pageStarts(ctx); return if (s.isEmpty()) 0 else s[(page - 1).coerceIn(0, s.size - 1)] }
    fun pageOf(ctx: Context, idx: Int): Int { val s = pageStarts(ctx); var r = 1; for (i in s.indices) if (s[i] <= idx) r = i + 1; return r }

    fun cur(st: Store): Int { val n = load(st.appCtx).size.coerceAtLeast(1); val v = if (st.vidx < 0) startOf(st.appCtx, st.page) else st.vidx; return v.coerceIn(0, n - 1) }

    fun advance(st: Store) {
        val n = load(st.appCtx).size.coerceAtLeast(1)
        val next = cur(st) + st.vcount.coerceAtLeast(1)
        val idx = if (next >= n) 0 else next
        st.vidx = idx; st.page = pageOf(st.appCtx, idx)
    }

    fun label(st: Store): String { val a = load(st.appCtx); val v = a.getOrNull(cur(st)) ?: return ""; return "سورة " + Wird.surah(v.sura) + " · آية " + ar(v.aya) }

    private fun ar(n: Int): String { val d = "٠١٢٣٤٥٦٧٨٩"; return n.toString().map { d[it - '0'] }.joinToString("") }

    private fun typeface(ctx: Context): Typeface = face ?: (try { Typeface.createFromAsset(ctx.applicationContext.assets, "AmiriQuran.ttf") } catch (_: Throwable) { Typeface.DEFAULT }).also { face = it }

    /** returns the scrolling verse view and the subtitle text */
    fun view(ctx: Context, st: Store): Pair<View, String> {
        val a = load(ctx); val start = cur(st); val n = st.vcount.coerceAtLeast(1)
        val sb = SpannableStringBuilder()
        var first = ""; var lastSura = -1
        var i = start; var shown = 0
        while (i < a.size && shown < n) {
            val v = a[i]
            if (v.sura != lastSura) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                val h = "سورة " + Wird.surah(v.sura) + "\n"; val s0 = sb.length; sb.append(h)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#0F5C4A")), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (first.isEmpty()) first = Wird.surah(v.sura)
                lastSura = v.sura
            }
            sb.append(v.text).append(' ')
            val m0 = sb.length; sb.append("\uFD3F" + ar(v.aya) + "\uFD3E ")
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#0F5C4A")), m0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), m0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i++; shown++
        }
        val d = ctx.resources.displayMetrics.density
        val tv = TextView(ctx)
        tv.text = sb; tv.typeface = typeface(ctx); tv.textSize = 25f; tv.setTextColor(Color.parseColor("#1d1d1d"))
        tv.setLineSpacing(0f, 1.7f); tv.layoutDirection = View.LAYOUT_DIRECTION_RTL; tv.textDirection = View.TEXT_DIRECTION_RTL
        tv.setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        val sc = ScrollView(ctx); sc.addView(tv)
        val sub = ar(shown) + " آيات · سورة " + first
        return Pair(sc, sub)
    }
}
