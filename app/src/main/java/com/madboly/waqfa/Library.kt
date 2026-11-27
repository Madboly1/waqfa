package com.madboly.waqfa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object Library {
    class Book(val id: String, val title: String, val type: String, var count: Int, var cur: Int)

    private const val PAGE_CHARS = 900

    fun list(st: Store): List<Book> = try {
        val a = JSONArray(st.booksJson)
        (0 until a.length()).map { val o = a.getJSONObject(it); Book(o.getString("id"), o.getString("title"), o.getString("type"), o.getInt("count"), o.getInt("cur")) }
    } catch (_: Throwable) { emptyList() }

    private fun save(st: Store, l: List<Book>) {
        val a = JSONArray()
        for (b in l) a.put(JSONObject().put("id", b.id).put("title", b.title).put("type", b.type).put("count", b.count).put("cur", b.cur))
        st.booksJson = a.toString()
    }

    fun current(st: Store): Book? { val l = list(st); return l.firstOrNull { it.id == st.curBook } ?: l.firstOrNull() }

    /** true when the next window should show a book page */
    fun nextIsBook(st: Store): Boolean {
        if (st.mode == "quran") return false
        if (current(st) == null) return false
        return st.mode == "books" || st.altTurn == 1
    }

    fun setCurrent(st: Store, id: String) { st.curBook = id }

    fun setPage(st: Store, id: String, page: Int) {
        val l = list(st); l.firstOrNull { it.id == id }?.let { it.cur = page.coerceIn(1, it.count.coerceAtLeast(1)) }; save(st, l)
    }

    fun advance(st: Store, id: String) {
        val l = list(st); l.firstOrNull { it.id == id }?.let { it.cur = if (it.cur + 1 > it.count) 1 else it.cur + 1 }; save(st, l)
    }

    fun delete(ctx: Context, st: Store, id: String) {
        save(st, list(st).filter { it.id != id })
        File(dir(ctx), "$id.pdf").delete(); File(dir(ctx), "$id.txt").delete()
        if (st.curBook == id) st.curBook = ""
    }

    /** run f off the main thread, deliver the result on the main thread */
    fun <T> async(f: () -> T, cb: (T) -> Unit) {
        val mh = android.os.Handler(android.os.Looper.getMainLooper())
        Thread { val r = try { f() } catch (e: Throwable) { null }; mh.post { try { @Suppress("UNCHECKED_CAST") cb(r as T) } catch (_: Throwable) {} } }.start()
    }

    private fun dir(ctx: Context): File { val d = File(ctx.filesDir, "books"); d.mkdirs(); return d }

    /** returns error text or null. Run off the main thread. */
    fun add(ctx: Context, st: Store, uri: Uri): String? {
        try {
            var name = "book"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) ?: name } }
            val lower = name.lowercase()
            val mime = ctx.contentResolver.getType(uri) ?: ""
            val type = when {
                lower.endsWith(".pdf") || mime == "application/pdf" -> "pdf"
                lower.endsWith(".epub") || mime == "application/epub+zip" -> "epub"
                lower.endsWith(".txt") || mime.startsWith("text/") -> "txt"
                else -> return "صيغة غير مدعومة، استخدم PDF أو EPUB أو TXT"
            }
            val id = java.lang.Long.toString(System.currentTimeMillis(), 36)
            val raw = File(dir(ctx), "$id.raw")
            ctx.contentResolver.openInputStream(uri)?.use { i -> raw.outputStream().use { o -> i.copyTo(o) } } ?: return "تعذر فتح الملف"
            if (raw.length() > 150L * 1024 * 1024) { raw.delete(); return "الملف كبير جدًا (أكثر من 150 م.ب)" }
            var title = name.substringBeforeLast('.')
            val count: Int
            when (type) {
                "pdf" -> {
                    val f = File(dir(ctx), "$id.pdf"); raw.renameTo(f)
                    val r = PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
                    count = r.pageCount; r.close()
                    if (count < 1) { f.delete(); return "ملف PDF فارغ" }
                }
                "epub" -> {
                    val ex = extractEpub(raw); raw.delete()
                    if (ex.second.isBlank()) return "تعذر قراءة نص الكتاب (قد يكون محميًا)"
                    if (ex.first.isNotBlank()) title = ex.first.trim()
                    val pages = paginate(ex.second); File(dir(ctx), "$id.txt").writeText(pages.joinToString("\u000C")); count = pages.size
                }
                else -> {
                    val bytes = raw.readBytes(); raw.delete()
                    var t = String(bytes, Charsets.UTF_8)
                    if (t.count { it == '\uFFFD' } > t.length / 200) t = String(bytes, charset("windows-1256"))
                    if (t.isBlank()) return "الملف فارغ"
                    val pages = paginate(t.replace("\r\n", "\n").replace('\r', '\n')); File(dir(ctx), "$id.txt").writeText(pages.joinToString("\u000C")); count = pages.size
                }
            }
            val l = list(st).toMutableList(); l.add(Book(id, title.take(80), type, count, 1)); save(st, l)
            if (st.curBook.isEmpty() || list(st).size == 1) st.curBook = id
            return null
        } catch (e: Throwable) { return "فشل إضافة الكتاب (" + e.javaClass.simpleName + ")" }
    }

    private fun paginate(t: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        fun flush() { val s = sb.toString().trim(); if (s.isNotEmpty()) out.add(s); sb.setLength(0) }
        for (raw in t.split("\n")) {
            var p = raw.trim()
            if (p.isEmpty()) { if (sb.isNotEmpty() && sb.length > PAGE_CHARS * 2 / 3) flush() else if (sb.isNotEmpty()) sb.append("\n"); continue }
            while (p.length > PAGE_CHARS) {
                var cut = p.lastIndexOf(' ', PAGE_CHARS); if (cut < PAGE_CHARS / 2) cut = PAGE_CHARS
                if (sb.isNotEmpty()) flush()
                out.add(p.substring(0, cut).trim()); p = p.substring(cut).trim()
            }
            if (sb.length + p.length + 1 > PAGE_CHARS) flush()
            if (sb.isNotEmpty()) sb.append("\n"); sb.append(p)
        }
        flush()
        return if (out.isEmpty()) listOf("") else out
    }

    private fun attr(tag: String, n: String) = Regex("\\b$n\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun extractEpub(f: File): Pair<String, String> {
        ZipFile(f).use { z ->
            val cont = z.getEntry("META-INF/container.xml")?.let { z.getInputStream(it).bufferedReader().readText() } ?: return Pair("", "")
            val opfPath = attr(Regex("<rootfile\\b[^>]*>").find(cont)?.value ?: "", "full-path") ?: return Pair("", "")
            val opf = z.getEntry(opfPath)?.let { z.getInputStream(it).bufferedReader().readText() } ?: return Pair("", "")
            val base = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') + "/" else ""
            val title = Regex("<dc:title[^>]*>([^<]*)</dc:title>").find(opf)?.groupValues?.get(1) ?: ""
            val items = HashMap<String, String>()
            for (m in Regex("<item\\b[^>]*>").findAll(opf)) { val id = attr(m.value, "id"); val href = attr(m.value, "href"); if (id != null && href != null) items[id] = href }
            val sb = StringBuilder()
            for (m in Regex("<itemref\\b[^>]*>").findAll(opf)) {
                val href = items[attr(m.value, "idref") ?: continue] ?: continue
                val path = base + Uri.decode(href).substringBefore('#')
                val e = z.getEntry(path) ?: continue
                var h = z.getInputStream(e).bufferedReader().readText()
                h = h.replace(Regex("(?is)<head.*?</head>"), "").replace(Regex("(?is)<script.*?</script>"), "").replace(Regex("(?is)<style.*?</style>"), "")
                val tx = Html.fromHtml(h, Html.FROM_HTML_MODE_LEGACY).toString().replace('\uFFFC', ' ').replace(Regex("\n{3,}"), "\n\n").trim()
                if (tx.isNotEmpty()) sb.append(tx).append("\n\n")
            }
            return Pair(Html.fromHtml(title, Html.FROM_HTML_MODE_LEGACY).toString(), sb.toString())
        }
    }

    fun textPage(ctx: Context, b: Book, page: Int): String = try {
        File(dir(ctx), "${b.id}.txt").readText().split("\u000C").getOrElse(page - 1) { "" }
    } catch (_: Throwable) { "تعذر قراءة الصفحة" }

    fun pdfPage(ctx: Context, b: Book, page: Int, widthPx: Int): Bitmap? = try {
        val r = PdfRenderer(ParcelFileDescriptor.open(File(dir(ctx), "${b.id}.pdf"), ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            val p = r.openPage((page - 1).coerceIn(0, r.pageCount - 1))
            val w = widthPx.coerceIn(400, 1080); val h = (w.toLong() * p.height / p.width).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); p.close(); bmp
        } finally { r.close() }
    } catch (_: Throwable) { null }
}
