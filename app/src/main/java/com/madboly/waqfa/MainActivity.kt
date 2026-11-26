package com.madboly.waqfa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: Store
    private val h = Handler(Looper.getMainLooper())
    private var screen = "home"
    private var status: TextView? = null
    private var toggle: Button? = null
    private var perms: LinearLayout? = null
    private var presetRow: LinearLayout? = null
    private var pause: CheckBox? = null
    private var durField: EditText? = null
    private var pageField: EditText? = null
    private var accountText: TextView? = null
    private var backupText: TextView? = null
    private var prayerText: TextView? = null
    private var permSig = ""
    private var backAt = 0L
    private var obPage = 0
    private val green = Color.parseColor("#0F5C4A")
    private val gold = Color.parseColor("#C9A24A")
    private val cream = Color.parseColor("#FBF7EC")

    private fun num(t: String): Int? {
        val sb = StringBuilder()
        for (c in t.trim()) sb.append(when (c) { in '\u0660'..'\u0669' -> '0' + (c - '\u0660'); in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0'); else -> c })
        return sb.toString().toIntOrNull()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun btn(t: String, filled: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(this); b.text = t; b.isAllCaps = false
        b.setTextColor(if (filled) Color.WHITE else green)
        b.background = GradientDrawable().apply { setColor(if (filled) green else Color.parseColor("#E9E2CE")); cornerRadius = dp(12).toFloat() }
        b.setOnClickListener { onClick() }
        return b
    }
    private fun lp(top: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun label(t: String, size: Float = 15f, bold: Boolean = false): TextView {
        val v = TextView(this); v.text = t; v.textSize = size; v.setTextColor(Color.parseColor("#222222"))
        if (bold) v.setTypeface(v.typeface, android.graphics.Typeface.BOLD); return v
    }
    private fun card(): LinearLayout {
        val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL; c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
        return c
    }
    private fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(this); sv.setBackgroundColor(cream); sv.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(36), dp(20), dp(28)); sv.addView(col)
        return Pair(sv, col)
    }
    private fun numField(hint: String, value: String): EditText {
        val e = EditText(this); e.hint = hint; e.inputType = InputType.TYPE_CLASS_NUMBER; e.setText(value); return e
    }
    private fun textRow(e: EditText, saveTxt: String, onSave: () -> Unit): LinearLayout {
        val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
        r.addView(e, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(btn(saveTxt, false, onSave)); return r
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        Background.scheduleAll(this)
        handleIntent(intent)
        if (!store.onboarded) showOnboarding() else showHome()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleIntent(i) }

    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != Drive.SCHEME) return
        i.data = null
        val code = d.getQueryParameter("code")
        if (code == null) { toast("تم إلغاء تسجيل الدخول"); return }
        toast("جاري تسجيل الدخول...")
        Thread {
            val err = Drive.exchange(store, code)
            var msg = err ?: "تم تسجيل الدخول"
            if (err == null) { val e2 = Drive.backup(store); store.backupMsg = e2 ?: ""; if (e2 != null) msg = e2 }
            runOnUiThread { toast(msg); if (screen == "settings") updateBackupViews() }
        }.start()
    }

    // ---------------- Home ----------------
    private fun showHome() {
        screen = "home"
        val (sv, col) = page()
        val title = label("وَقفَة", 34f, true); title.setTextColor(green); title.gravity = Gravity.CENTER
        col.addView(title, lp(0))
        val c1 = card(); status = label("", 16f); c1.addView(status); col.addView(c1, lp(14))
        toggle = btn("") { toggleService() }; col.addView(toggle, lp(10))

        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        fun stat(t: String, v: String): LinearLayout {
            val c = card(); c.addView(label(t, 12f).apply { setTextColor(Color.GRAY) }); c.addView(label(v, 28f, true).apply { setTextColor(gold) }); return c
        }
        val wk = (0..6).sumOf { val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -it); store.pagesOn(c) }
        row.addView(stat("أيام متتالية", store.streak().toString()), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(6), 0) })
        row.addView(stat("صفحات هذا الأسبوع", wk.toString()), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        col.addView(row, lp(14))

        val ch = card(); ch.addView(label("آخر ٧ أيام", 12f).apply { setTextColor(Color.GRAY) })
        val bars = LinearLayout(this); bars.orientation = LinearLayout.HORIZONTAL; bars.gravity = Gravity.BOTTOM
        val vals = (6 downTo 0).map { val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -it); store.pagesOn(c) }
        val mx = (vals.maxOrNull() ?: 1).coerceAtLeast(1)
        for (v in vals) {
            val bar = View(this); bar.background = GradientDrawable().apply { setColor(if (v > 0) gold else Color.parseColor("#E9E2CE")); cornerRadius = dp(3).toFloat() }
            bars.addView(bar, LinearLayout.LayoutParams(0, dp(6 + 64 * v / mx), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        ch.addView(bars, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(8) })
        col.addView(ch, lp(12))

        val c3 = card()
        val avg = if (store.doneCount > 0) (store.sumMs / store.doneCount / 60000).toString() + " د" else "-"
        c3.addView(label("إجمالي الصفحات: ${store.totalPages}", 15f))
        c3.addView(label("متوسط وقت الشاشة قبل الورد: $avg", 15f), lp(6))
        c3.addView(label("مرات «لاحقًا»: ${store.laterCount}", 15f), lp(6))
        col.addView(c3, lp(12))
        col.addView(btn("⚙ الإعدادات", false) { showSettings() }, lp(16))
        setContentView(sv); refresh()
    }

    // ---------------- Settings ----------------
    private fun section(col: LinearLayout, title: String, open: Boolean, build: (LinearLayout) -> Unit) {
        val head = TextView(this); head.text = (if (open) "▾  " else "▸  ") + title; head.textSize = 17f; head.setTextColor(green)
        head.setTypeface(head.typeface, android.graphics.Typeface.BOLD); head.setPadding(dp(14), dp(12), dp(14), dp(12))
        head.background = GradientDrawable().apply { setColor(Color.parseColor("#EFE7CF")); cornerRadius = dp(12).toFloat() }
        val body = LinearLayout(this); body.orientation = LinearLayout.VERTICAL; body.setPadding(dp(4), dp(4), dp(4), dp(8))
        build(body); body.visibility = if (open) View.VISIBLE else View.GONE
        head.setOnClickListener { val o = body.visibility == View.VISIBLE; body.visibility = if (o) View.GONE else View.VISIBLE; head.text = (if (o) "▸  " else "▾  ") + title }
        col.addView(head, lp(12)); col.addView(body, lp(2))
    }

    private fun showSettings() {
        screen = "settings"
        val (sv, col) = page()
        val title = label("⚙ الإعدادات", 26f, true); title.setTextColor(green); col.addView(title, lp(0))

        section(col, "الورد", true) { s ->
            presetRow = LinearLayout(this); presetRow!!.orientation = LinearLayout.HORIZONTAL
            s.addView(label("المدة", 15f, true)); s.addView(presetRow, lp(4))
            val et = numField("مدة مخصصة بالدقائق", store.targetMin.toString()); durField = et
            s.addView(textRow(et, "حفظ") {
                val v = num(et.text.toString())
                if (v != null && v > 0) { store.targetMin = v; et.setText(v.toString()); refresh(); toast("تم الحفظ: $v دقيقة") } else toast("اكتب عدد دقائق صحيح")
            }, lp(6))
            s.addView(label("الصفحة", 15f, true), lp(12))
            val pe = numField("ابدأ من صفحة (١ إلى ٦٠٤)", store.page.toString()); pageField = pe
            s.addView(textRow(pe, "حفظ") {
                val v = num(pe.text.toString())
                if (v != null && v in 1..604) { store.page = v; pe.setText(v.toString()); refresh(); toast("تم الحفظ: صفحة $v") } else toast("اكتب رقم من 1 إلى 604")
            }, lp(6))
            pause = CheckBox(this); pause!!.text = "إيقاف التنبيه لباقي اليوم"
            s.addView(pause, lp(12))
            s.addView(btn("جرّب النافذة الآن", false) { testOverlay() }, lp(8))
        }

        section(col, "أوقات الصلاة", false) { s ->
            val sw = CheckBox(this); sw.text = "تجنّب التنبيه وقت الصلاة"; sw.isChecked = store.prayerOn
            sw.setOnCheckedChangeListener { _, o -> store.prayerOn = o }
            s.addView(sw)
            val cityE = EditText(this); cityE.hint = "المدينة (بالإنجليزي مثل Cairo)"; cityE.setText(store.city)
            val ctyE = EditText(this); ctyE.hint = "الدولة (بالإنجليزي مثل Egypt)"; ctyE.setText(store.country)
            s.addView(cityE, lp(6)); s.addView(ctyE, lp(2))
            val bE = numField("دقائق قبل الصلاة", store.pBefore.toString()); val aE = numField("دقائق بعد الصلاة", store.pAfter.toString())
            s.addView(label("المواقيت: الهيئة المصرية العامة للمساحة، وتُحمّل من الإنترنت وتُحفظ على الجهاز", 12f).apply { setTextColor(Color.GRAY) }, lp(4))
            val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL
            r.addView(bE, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(aE, LinearLayout.LayoutParams(0, -2, 1f)); s.addView(r, lp(6))
            prayerText = label(store.prayerMsg, 13f); s.addView(prayerText, lp(6))
            s.addView(btn("حفظ وتحديث المواقيت", false) {
                val cy = cityE.text.toString().trim(); val co = ctyE.text.toString().trim()
                if (cy.isEmpty() || co.isEmpty()) { toast("اكتب المدينة والدولة"); return@btn }
                store.city = cy; store.country = co
                num(bE.text.toString())?.let { store.pBefore = it }; num(aE.text.toString())?.let { store.pAfter = it }
                bE.setText(store.pBefore.toString()); aE.setText(store.pAfter.toString())
                prayerText?.text = "جاري التحميل..."
                Thread { val ok = Prayer.fetchNow(store); runOnUiThread { prayerText?.text = store.prayerMsg; toast(if (ok) "تم" else "فشل التحميل") } }.start()
            }, lp(8))
        }

        section(col, "النسخ الاحتياطي", false) { s ->
            accountText = label("", 14f); s.addView(accountText)
            s.addView(btn("تسجيل الدخول بجوجل", true) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Drive.authUrl(store)))) }, lp(6))
            val au = CheckBox(this); au.text = "نسخ تلقائي يومي إلى درايف"; au.isChecked = store.autoBackup
            au.setOnCheckedChangeListener { _, o -> store.autoBackup = o }
            s.addView(au, lp(8))
            backupText = label("", 13f); s.addView(backupText, lp(4))
            s.addView(btn("نسخ الآن", false) {
                toast("جاري النسخ..."); Thread { val e = Drive.backup(store); store.backupMsg = e ?: ""; runOnUiThread { toast(e ?: "تم النسخ"); updateBackupViews() } }.start()
            }, lp(8))
            s.addView(btn("استعادة من درايف", false) {
                toast("جاري الاستعادة..."); Thread { val e = Drive.restore(store); runOnUiThread { toast(e ?: "تمت الاستعادة"); if (e == null) showSettings() } }.start()
            }, lp(6))
            s.addView(btn("تسجيل الخروج", false) { store.refreshToken = ""; store.email = ""; updateBackupViews() }, lp(6))
            s.addView(label("نسخة محلية", 14f, true), lp(14))
            s.addView(btn("تصدير ملف", false) {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "waqfa-backup.json")
                startActivityForResult(i, 11)
            }, lp(6))
            s.addView(btn("استيراد ملف", false) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 12)
            }, lp(6))
            s.addView(label("يُحفظ: الإعدادات والصفحة الحالية والإحصائيات، في مساحة التطبيق الخاصة في درايف.", 12f).apply { setTextColor(Color.GRAY) }, lp(8))
            updateBackupViews()
        }

        section(col, "الأذونات والبطارية", false) { s -> perms = LinearLayout(this); perms!!.orientation = LinearLayout.VERTICAL; s.addView(perms) }

        section(col, "عن التطبيق", false) { s ->
            s.addView(btn("تحقق من التحديث", false) { checkUpdate() }, lp(6))
            s.addView(btn("عرض الشرح", false) { obPage = 0; showOnboarding() }, lp(6))
            val ver = label("الإصدار ${packageManager.getPackageInfo(packageName, 0).versionName}", 12f); ver.gravity = Gravity.CENTER; s.addView(ver, lp(10))
            val credit = label("صفحات المصحف: مصحف المدينة النبوية، مجمع الملك فهد لطباعة المصحف الشريف", 11f)
            credit.gravity = Gravity.CENTER; credit.setTextColor(Color.GRAY); s.addView(credit, lp(8))
        }
        setContentView(sv); permSig = ""; refresh()
    }

    private fun updateBackupViews() {
        accountText?.text = if (store.email.isNotEmpty()) "الحساب: " + store.email else if (store.refreshToken.isNotEmpty()) "مسجّل الدخول" else "غير مسجّل الدخول"
        val last = if (store.lastBackup > 0) "آخر نسخة: " + SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(store.lastBackup)) else "لا توجد نسخة بعد"
        backupText?.text = last + (if (store.backupMsg.isNotEmpty()) "\n" + store.backupMsg else "")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rs: Int, data: Intent?) {
        super.onActivityResult(rq, rs, data)
        val u = data?.data ?: return
        if (rs != RESULT_OK) return
        try {
            if (rq == 11) { contentResolver.openOutputStream(u)?.use { it.write(store.exportJson().toByteArray()) }; toast("تم التصدير") }
            if (rq == 12) { val t = contentResolver.openInputStream(u)!!.bufferedReader().use { it.readText() }; store.importJson(t); toast("تم الاستيراد"); showSettings() }
        } catch (e: Throwable) { toast("فشل: " + e.javaClass.simpleName) }
    }

    // ---------------- Onboarding ----------------
    private fun showOnboarding() {
        screen = "onboarding"
        val (sv, col) = page()
        val skip = label("تخطي", 14f); skip.setTextColor(Color.GRAY); skip.setOnClickListener { finishOnboarding() }
        col.addView(skip, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START })
        val big = label("", 70f); big.gravity = Gravity.CENTER; col.addView(big, lp(40))
        val ttl = label("", 24f, true); ttl.gravity = Gravity.CENTER; ttl.setTextColor(green); col.addView(ttl, lp(10))
        val body = label("", 17f); body.gravity = Gravity.CENTER; col.addView(body, lp(12))
        val extra = LinearLayout(this); extra.orientation = LinearLayout.VERTICAL; col.addView(extra, lp(16))
        val dots = label("", 28f); dots.gravity = Gravity.CENTER; dots.setTextColor(gold); col.addView(dots, lp(24))
        val next = btn("التالي") {}
        col.addView(next, lp(10))
        fun render() {
            extra.removeAllViews()
            dots.text = (0..3).joinToString(" ") { if (it == obPage) "●" else "○" }
            when (obPage) {
                0 -> { big.text = "⏸"; ttl.text = "وَقفَة بين شاشتك وورد القرآن"; body.text = "بعد مدة استخدام للموبايل تظهر نافذة صغيرة بصفحة واحدة من المصحف فوق أي تطبيق. اقرأ الصفحة واضغط «قرأت وردي»." }
                1 -> {
                    big.text = "⏱"; ttl.text = "اختر المدة"; body.text = "المدة هي وقت استخدام الشاشة المتراكم قبل كل ورد."
                    val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL
                    for ((t, m) in listOf("١٥ د" to 15, "٣٠ د" to 30, "ساعة" to 60, "ساعتين" to 120)) {
                        r.addView(btn(t, store.targetMin == m) { store.targetMin = m; render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
                    }
                    extra.addView(r)
                }
                2 -> {
                    big.text = "🔓"; ttl.text = "الأذونات"; body.text = "تحتاج وَقفَة هذه الأذونات لتعمل في الخلفية."
                    extra.addView(btn("الظهور فوق التطبيقات (مطلوب)", false) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }, lp(6))
                    extra.addView(btn("استثناء من توفير البطارية", false) { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }, lp(6))
                    extra.addView(label("في شاومي: فعّل أيضًا «التشغيل التلقائي» للتطبيق من إعدادات التطبيقات.", 13f).apply { setTextColor(Color.GRAY); gravity = Gravity.CENTER }, lp(8))
                }
                else -> {
                    big.text = "☁"; ttl.text = "نسخة احتياطية"; body.text = "سجّل الدخول بجوجل لحفظ إعداداتك وإحصائياتك في درايف تلقائيًا. يمكنك تأجيل هذا."
                    extra.addView(btn("تسجيل الدخول بجوجل", true) { finishOnboarding(); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Drive.authUrl(store)))) }, lp(6))
                }
            }
            next.text = if (obPage == 3) "ابدأ" else "التالي"
        }
        next.setOnClickListener { if (obPage < 3) { obPage++; render() } else finishOnboarding() }
        render(); setContentView(sv)
    }
    private fun finishOnboarding() { store.onboarded = true; showHome() }

    // ---------------- actions ----------------
    private fun toggleService() {
        if (store.enabled) { store.enabled = false; stopService(Intent(this, WaqfaService::class.java)) }
        else {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "اسمح بالظهور فوق التطبيقات أولًا (الإعدادات ثم الأذونات)", Toast.LENGTH_LONG).show(); refresh(); return }
            store.enabled = true; startForegroundService(Intent(this, WaqfaService::class.java))
        }
        refresh()
    }

    private fun testOverlay() {
        if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "اسمح بالظهور فوق التطبيقات أولًا", Toast.LENGTH_LONG).show(); return }
        startForegroundService(Intent(this, WaqfaService::class.java).putExtra("test", true))
        if (!store.enabled) toast("تجربة فقط، التنبيه التلقائي متوقف")
    }

    private fun checkUpdate() {
        toast("جاري التحقق...")
        Thread {
            var msg: String
            try {
                val info = Updater.check()
                val cur = packageManager.getPackageInfo(packageName, 0).let { @Suppress("DEPRECATION") it.versionCode }
                if (info == null) msg = "لا توجد قناة تحديث متاحة حاليًا"
                else if (info.code <= cur) msg = "أنت على آخر إصدار"
                else {
                    runOnUiThread { Toast.makeText(this, "جاري تنزيل الإصدار ${info.name}", Toast.LENGTH_LONG).show() }
                    Updater.install(this, info.url); msg = "تم التنزيل، أكمل التثبيت"
                }
            } catch (e: Exception) { msg = "تعذر التحقق من التحديث" }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun permRow(text: String, action: () -> Unit) { perms?.addView(btn(text, false, action), lp(6)) }

    private fun refresh() {
        val on = store.enabled
        val sec = store.accMs / 1000
        val tgt = store.targetMin
        fun mmss(x: Long) = String.format(Locale.US, "%d:%02d", x / 60, x % 60)
        val age = (System.currentTimeMillis() - store.lastTick) / 1000
        val svc = if (!on) "متوقفة" else if (store.lastTick > 0 && age < 20) "تعمل ✓" else "مفعّلة (بتشتغل...)"
        val err = if (store.lastErr.isNotEmpty()) "\nآخر خطأ: " + store.lastErr else ""
        val hold = if (on && store.holdMsg.isNotEmpty()) "\n" + store.holdMsg else ""
        status?.text = svc + err + hold + "\nالعداد: ${mmss(sec)} من ${mmss(tgt * 60L)}\nالصفحة القادمة: ${store.page}"
        toggle?.text = if (on) "إيقاف وَقفَة" else "تشغيل وَقفَة"
        if (screen != "settings") return
        presetRow?.let { pr ->
            pr.removeAllViews()
            for ((t, m) in listOf("١٥ د" to 15, "٣٠ د" to 30, "ساعة" to 60, "ساعتين" to 120)) {
                val b = btn(t, store.targetMin == m) { store.targetMin = m; refresh() }
                pr.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
        }
        durField?.let { f -> if (!f.hasFocus()) { val t = store.targetMin.toString(); if (f.text.toString() != t) f.setText(t) } }
        pageField?.let { f -> if (!f.hasFocus()) { val t = store.page.toString(); if (f.text.toString() != t) f.setText(t) } }
        pause?.let { p -> p.setOnCheckedChangeListener(null); p.isChecked = store.pausedToday(); p.setOnCheckedChangeListener { _, o -> store.setPausedToday(o) } }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val sig = "" + Settings.canDrawOverlays(this) + pm.isIgnoringBatteryOptimizations(packageName) + packageManager.canRequestPackageInstalls()
        if (sig != permSig) {
            permSig = sig
            perms?.removeAllViews()
            if (!Settings.canDrawOverlays(this)) permRow("السماح بالظهور فوق التطبيقات (مطلوب)") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            if (!pm.isIgnoringBatteryOptimizations(packageName)) permRow("استثناء من توفير البطارية (يُنصح به)") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            if (!packageManager.canRequestPackageInstalls()) permRow("السماح بتثبيت التحديثات من التطبيق") {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            if (perms?.childCount == 0) perms?.addView(label("كل الأذونات تمام ✓", 14f))
        }
    }

    private val ticker = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }
    override fun onResume() {
        super.onResume()
        if (store.enabled) try { startForegroundService(Intent(this, WaqfaService::class.java)) } catch (_: Exception) {}
        if (screen == "home") showHome()
        h.post(ticker)
    }
    override fun onPause() { h.removeCallbacks(ticker); super.onPause() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == "settings") { showHome(); return }
        if (screen == "onboarding") { finishOnboarding(); return }
        val now = System.currentTimeMillis()
        if (now - backAt < 2000) finish() else { backAt = now; toast("اضغط مرة أخرى للخروج") }
    }
}
