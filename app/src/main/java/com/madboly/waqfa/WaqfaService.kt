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
            val hold = Prayer.blockedUntil(store)
            store.holdMsg = if (hold != null) "مؤجّل لوقت الصلاة حتى $hold" else ""
            if (active) {
                if (overlay == null) store.accMs = store.accMs + d
                if (overlay == null && hold == null && store.accMs >= store.targetMin * 60000L && !store.pausedToday() && !inCall()) {
                    if (Settings.canDrawOverlays(this@WaqfaService)) showOverlay() else notifyFallback()
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

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        val ch = NotificationChannel("waqfa", "وَقفَة", NotificationManager.IMPORTANCE_MIN)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "waqfa")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("وَقفَة تعمل")
            .setContentIntent(open).setOngoing(true).build()
        try { startForeground(1, n) } catch (e: Throwable) { store.lastErr = "fgs: " + e.javaClass.simpleName; stopSelf(); return START_NOT_STICKY }
        try { Background.scheduleAll(this) } catch (e: Throwable) { store.lastErr = "sched: " + e.javaClass.simpleName }
        if (i?.getBooleanExtra("test", false) == true) { if (overlay == null) { testMode = true; showOverlay() } }
        last = System.currentTimeMillis()
        h.removeCallbacks(tick); h.post(tick)
        return START_STICKY
    }

    private fun notifyFallback() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("waqfa_alert", "تنبيه الورد", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(this, "waqfa_alert").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("وقت وردك").setContentText("اسمح بالظهور فوق التطبيقات من وَقفَة عشان تظهر الصفحة").setContentIntent(open).setAutoCancel(true).build())
        store.lastErr = "overlay permission missing"
        store.accMs = (store.targetMin - store.snoozeMin).coerceAtLeast(0) * 60000L
    }

    private fun inCall(): Boolean {
        val m = (getSystemService(AUDIO_SERVICE) as AudioManager).mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) { store.lastErr = "overlay permission missing"; return }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val page = store.page
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(170, 0, 0, 0))
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply { setColor(Color.parseColor("#FBF7EC")); cornerRadius = dp(22).toFloat() }
        card.clipToOutline = true
        val hd = TextView(this)
        hd.text = "وردك الآن  ·  صفحة $page"
        hd.setTextColor(Color.WHITE); hd.textSize = 17f; hd.gravity = Gravity.CENTER
        hd.setBackgroundColor(Color.parseColor("#0F5C4A"))
        hd.setPadding(dp(16), dp(12), dp(16), dp(12))
        card.addView(hd, LinearLayout.LayoutParams(-1, -2))
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setPadding(dp(6), dp(6), dp(6), dp(6))
        try {
            val name = String.format(java.util.Locale.US, "pages/p%03d.webp", page)
            val bmp = assets.open(name).use { BitmapFactory.decodeStream(it) }
            if (bmp == null) store.lastErr = "page decode failed: $page" else img.setImageBitmap(bmp)
        } catch (e: Throwable) { store.lastErr = "page: " + e.javaClass.simpleName + " " + (e.message ?: "") }
        card.addView(img, LinearLayout.LayoutParams(-1, 0, 1f))
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
            if (!testMode) { store.recordDone(store.accMs); store.page = page + 1; store.accMs = 0L }; hideOverlay()
        }
        later.setOnClickListener {
            if (!testMode) { store.laterCount = store.laterCount + 1; store.accMs = (store.targetMin - store.snoozeMin).coerceAtLeast(0) * 60000L }; hideOverlay()
        }
        val lp = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        bigView = root
        try { wm.addView(root, lp); overlay = root; store.lastErr = "" } catch (e: Throwable) { overlay = null; store.lastErr = "overlay: " + e.javaClass.simpleName + " " + (e.message ?: "") }
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
        try { wm.addView(t, lp); bubble = t } catch (e: Throwable) { store.lastErr = "bubble: " + e.javaClass.simpleName; try { wm.addView(big, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)) } catch (_: Exception) {} }
    }

    private fun restore() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }; bubble = null
        val big = bigView ?: return
        try { wm.addView(big, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)) } catch (e: Throwable) { store.lastErr = "restore: " + e.javaClass.simpleName }
    }

    private fun hideOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }; bubble = null
        bigView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; bigView = null
        overlay = null; testMode = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) { Background.scheduleWatchdog(this); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        h.removeCallbacks(tick); hideOverlay(); super.onDestroy()
    }
}
