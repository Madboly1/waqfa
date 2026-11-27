package com.madboly.waqfa

import android.app.*
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.app.KeyguardManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView

class WaqfaService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private var last = 0L
    private var overlay: View? = null
    private var bubble: View? = null
    private var bigView: View? = null
    private var testMode = false
    private var lastWhy = "?"
    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (e: Throwable) { store.lastErr = "tick: " + e.javaClass.simpleName + " " + (e.message ?: "") }
            h.postDelayed(this, 5000)
        }
        fun step() {
            val now = System.currentTimeMillis()
            store.lastTick = now
            val d = (now - last).coerceIn(0L, 15000L)
            last = now
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val active = pm.isInteractive && !km.isKeyguardLocked
            Prayer.ensure(store)
            val hold = if (Library.nextIsBook(store) && !store.booksPrayer) null else Prayer.blockedUntil(store)
            store.holdMsg = if (hold != null) "مؤجّل لوقت الصلاة حتى $hold" else ""
            if (store.overlayUp && overlay == null && bubble == null) { store.overlayUp = false; Log.i(this@WaqfaService, "cleared stuck window flag") }
            Background.accrue(this@WaqfaService, false)
            val smart = Wird.smartHold(this@WaqfaService, store)
            val call = inCall()
            val canDraw = Settings.canDrawOverlays(this@WaqfaService)
            val ready = store.accMs >= store.targetMin * 60000L
            store.why = when {
                !active -> "الشاشة مقفلة، العدّاد واقف"
                overlay != null || bubble != null -> "النافذة ظاهرة الآن"
                !canDraw -> "ناقص إذن الظهور فوق التطبيقات (فعّله من الإعدادات)"
                !ready -> ""
                hold != null -> "مؤجّل لوقت الصلاة حتى $hold"
                smart != null -> smart
                store.pausedToday() -> "موقوف لباقي اليوم (من الإعدادات)"
                call -> "مؤجّل: النظام في وضع مكالمة أو رنين"
                else -> ""
            }
            if (store.why != lastWhy) { lastWhy = store.why; Log.i(this@WaqfaService, "state: " + (if (lastWhy.isEmpty()) "ok" else lastWhy) + " acc=" + (store.accMs / 1000) + "s") }
            if (active) {
                if (overlay == null && hold == null && smart == null && ready && !store.pausedToday() && !call) {
                    if (canDraw) showOverlay() else notifyFallback()
                }
            }
            autoBackup(now)
        }
    }

    private var backing = false
    private fun autoBackup(now: Long) {
        if (backing || !store.autoBackup || store.refreshToken.isEmpty() || now - store.lastBackup < 24 * 3600 * 1000L) return
        if (now - store.lastBackupTry < 3600 * 1000L) return
        store.lastBackupTry = now
        backing = true
        Thread { try { val e = Drive.backup(store); store.backupMsg = e ?: "" } catch (_: Throwable) {} finally { backing = false } }.start()
    }

    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { Background.accrue(c, true) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        store.overlayUp = false
        Log.i(this, "service created")
        try { registerReceiver(screenRx, android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }) } catch (_: Throwable) {}
        val ch = NotificationChannel("waqfa2", "وَقفَة", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "waqfa2")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("وَقفَة تعمل").setContentText("تعدّ وقت الشاشة لورد اليوم")
            .setContentIntent(open).setOngoing(true).build()
        try { startForeground(1, n) } catch (e: Throwable) { store.lastErr = "fgs: " + e.javaClass.simpleName; stopSelf(); return START_NOT_STICKY }
        try { Background.scheduleAll(this) } catch (e: Throwable) { store.lastErr = "sched: " + e.javaClass.simpleName }
        if (i?.getBooleanExtra("test", false) == true) { if (overlay == null) { testMode = true; showOverlay() } }
        Log.i(this, "service started cmd")
        last = System.currentTimeMillis()
        h.removeCallbacks(tick); h.post(tick)
        return START_STICKY
    }

    private fun notifyFallback() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("waqfa_alert", "تنبيه الورد", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("reader", true), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(this, "waqfa_alert").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("وقت وردك").setContentText("اضغط لقراءة ورد اليوم (وللنافذة فوق التطبيقات اسمح بالظهور من الإعدادات)").setContentIntent(open).setAutoCancel(true).build())
        store.lastErr = "overlay permission missing"
        store.accMs = Wird.laterAcc(store)
    }

    private fun inCall(): Boolean {
        val m = (getSystemService(AUDIO_SERVICE) as AudioManager).mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) { store.lastErr = "overlay permission missing"; return }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val book = if (Library.nextIsBook(store)) Library.current(store) else null
        val page = if (book != null) book.cur else store.page
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(170, 0, 0, 0))
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply { setColor(Color.parseColor("#FBF7EC")); cornerRadius = dp(22).toFloat() }
        card.clipToOutline = true
        val hd = TextView(this)
        hd.text = if (book != null) "وردك الآن  ·  " + book.title + "  ·  صفحة $page من " + book.count else "وردك الآن  ·  صفحة $page"
        hd.setTextColor(Color.WHITE); hd.textSize = 17f; hd.gravity = Gravity.CENTER
        hd.setBackgroundColor(Color.parseColor("#0F5C4A"))
        hd.setPadding(dp(16), dp(12), dp(16), dp(12))
        card.addView(hd, LinearLayout.LayoutParams(-1, -2))
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setPadding(dp(6), dp(6), dp(6), dp(6))
        if (book != null && book.type != "pdf") {
            val tv = TextView(this); tv.text = Library.textPage(this, book, page); tv.textSize = 18f; tv.setTextColor(Color.parseColor("#222222"))
            tv.setLineSpacing(0f, 1.25f); tv.setPadding(dp(18), dp(14), dp(18), dp(14)); tv.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            val sc = ScrollView(this); sc.addView(tv)
            card.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            try {
                if (book != null) {
                    val bmp = Library.pdfPage(this, book, page, resources.displayMetrics.widthPixels)
                    if (bmp == null) store.lastErr = "pdf page failed: $page" else img.setImageBitmap(bmp)
                } else {
                    val name = String.format(java.util.Locale.US, "pages/p%03d.webp", page)
                    val bmp = assets.open(name).use { BitmapFactory.decodeStream(it) }
                    if (bmp == null) store.lastErr = "page decode failed: $page" else img.setImageBitmap(bmp)
                }
            } catch (e: Throwable) { store.lastErr = "page: " + e.javaClass.simpleName + " " + (e.message ?: "") }
            card.addView(img, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(10), dp(8), dp(10), dp(10))
        val done = Button(this); done.text = "قرأت وردي"
        done.setTextColor(Color.WHITE)
        done.background = GradientDrawable().apply { setColor(Color.parseColor("#0F5C4A")); cornerRadius = dp(12).toFloat() }
        val later = Button(this); later.text = "لاحقًا"
        later.background = GradientDrawable().apply { setColor(Color.parseColor("#DDD5BF")); cornerRadius = dp(12).toFloat() }
        row.addView(done, LinearLayout.LayoutParams(0, dp(48), 2f).apply { setMargins(dp(4), 0, dp(4), 0) })
        row.addView(later, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        root.addView(card, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(14), dp(40), dp(14), dp(40)); gravity = Gravity.CENTER })
        val mn = TextView(this); mn.text = "\u2013"; mn.textSize = 24f; mn.gravity = Gravity.CENTER; mn.setTextColor(Color.WHITE)
        mn.background = GradientDrawable().apply { setColor(Color.parseColor("#C9A24A")); shape = GradientDrawable.OVAL }
        root.addView(mn, FrameLayout.LayoutParams(dp(38), dp(38)).apply { gravity = Gravity.TOP or Gravity.RIGHT; setMargins(dp(22), dp(46), dp(22), 0) })
        mn.setOnClickListener { minimize(page) }
        done.setOnClickListener {
            Log.i(this, "done pressed"); if (!testMode) Wird.applyDone(store, book, page); hideOverlay()
        }
        later.setOnClickListener {
            Log.i(this, "later pressed"); if (!testMode) Wird.applyLater(store); hideOverlay()
        }
        val lp = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT)
        bigView = root
        try { wm.addView(root, lp); overlay = root; store.overlayUp = true; store.lastErr = ""; Wird.markShown(store); Log.i(this, "window shown, page $page"); checkVisible(root) } catch (e: Throwable) { overlay = null; store.lastErr = "overlay: " + e.javaClass.simpleName + " " + (e.message ?: ""); Log.i(this, "window FAILED: " + store.lastErr); alertNote(page); store.accMs = Wird.laterAcc(store) }
    }

    private fun alertNote(page: Int) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("waqfa_alert2", "تنبيه الورد", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("reader", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(3, Notification.Builder(this, "waqfa_alert2").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("وقت وردك").setContentText("اضغط لقراءة ورد اليوم").setContentIntent(open)
                .setFullScreenIntent(open, true).setCategory(Notification.CATEGORY_ALARM).setAutoCancel(true).build())
            Log.i(this, "alert notification posted")
        } catch (e: Throwable) { Log.i(this, "alert notification FAILED: " + e.javaClass.simpleName) }
    }

    private fun checkVisible(v: View) {
        h.postDelayed({
            try {
                val ok = v.isAttachedToWindow && v.isShown && v.windowVisibility == View.VISIBLE
                Log.i(this, "window check after 2s: attached=" + v.isAttachedToWindow + " shown=" + v.isShown + " winVis=" + v.windowVisibility + " size=" + v.width + "x" + v.height)
                if (!ok && overlay != null) {
                    hideOverlay()
                    store.accMs = Wird.laterAcc(store)
                    alertNote(store.page)
                    try { startActivity(Intent(this, MainActivity::class.java).putExtra("reader", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Log.i(this, "fallback: opened in-app reader") }
                    catch (e: Throwable) { Log.i(this, "fallback activity FAILED: " + e.javaClass.simpleName) }
                }
            } catch (_: Throwable) {}
        }, 2000)
    }

    private fun minimize(page: Int) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val big = bigView ?: return
        try { wm.removeView(big) } catch (_: Exception) {}
        val t = TextView(this); t.text = "\u23F8 ورد $page"; t.textSize = 16f; t.setTextColor(Color.WHITE)
        t.setPadding(dp(16), dp(10), dp(16), dp(10))
        t.background = GradientDrawable().apply { setColor(Color.parseColor("#0F5C4A")); cornerRadius = dp(24).toFloat() }
        val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = dp(8); lp.y = dp(220)
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        t.setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - sx) > dp(6) || Math.abs(e.rawY - sy) > dp(6)) moved = true
                    if (moved) { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); try { wm.updateViewLayout(v, lp) } catch (_: Exception) {} }
                }
                android.view.MotionEvent.ACTION_UP -> if (!moved) restore()
            }
            true
        }
        try { wm.addView(t, lp); bubble = t } catch (e: Throwable) { store.lastErr = "bubble: " + e.javaClass.simpleName; try { wm.addView(big, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT)) } catch (_: Exception) {} }
    }

    private fun restore() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }; bubble = null
        val big = bigView ?: return
        try { wm.addView(big, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT)) } catch (e: Throwable) { store.lastErr = "restore: " + e.javaClass.simpleName }
    }

    private fun hideOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }; bubble = null
        bigView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; bigView = null
        overlay = null; testMode = false; store.overlayUp = false
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(3) } catch (_: Throwable) {}
        Background.accrue(this, false)
    }

    override fun onTaskRemoved(rootIntent: Intent?) { Background.scheduleWatchdog(this); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        Log.i(this, "service destroyed"); h.removeCallbacks(tick); try { unregisterReceiver(screenRx) } catch (_: Throwable) {}; hideOverlay(); super.onDestroy()
    }
}
