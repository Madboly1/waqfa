package com.madboly.waqfa

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Everything that keeps Waqfa alive without the app being opened. */
object Background {
    fun startService(c: Context) {
        if (!Store(c).enabled) return
        try { c.startForegroundService(Intent(c, WaqfaService::class.java)) } catch (e: Throwable) { Store(c).lastErr = "start: " + e.javaClass.simpleName; Log.i(c, "service start FAILED: " + e.javaClass.simpleName) }
    }

    fun scheduleAll(c: Context) {
        try { val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.getPendingJob(77) == null) {
            js.schedule(JobInfo.Builder(77, ComponentName(c, MaintenanceJob::class.java))
                .setPeriodic(6 * 3600 * 1000L).setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        } } catch (_: Throwable) {}
        try { scheduleWatchdog(c) } catch (_: Throwable) {}
    }

    private const val CAP = 20 * 60 * 1000L

    /** Credits screen-on time since the last sample and re-arms the due alarm. Safe to call from anywhere, any time. */
    @Synchronized fun accrue(c: Context, event: Boolean) {
        try {
            val st = Store(c)
            val now = System.currentTimeMillis()
            val pm = c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val km = c.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            val act = pm.isInteractive && !km.isKeyguardLocked
            val gap = now - st.sAt
            var skip = ""
            if (st.sAt <= 0) skip = "first run"
            else if (st.overlayUp) skip = "window or bubble is up"
            else if (st.wallMode) { if (gap > 0) st.accMs = st.accMs + gap }
            else if (!st.sActive) skip = "previous state was screen off or locked"
            else if (!(event || act)) skip = "screen off or locked now"
            else if (gap > 0) st.accMs = st.accMs + gap.coerceAtMost(CAP)
            if (skip != "" && gap > 20000 && skip != lastSkip) { lastSkip = skip; Log.i(c, "counter skipped " + gap / 1000 + "s: " + skip) }
            if (skip == "") lastSkip = ""
            if (event) Log.i(c, "screen event, active=$act acc=${st.accMs / 1000}s")
            st.sAt = now; st.sActive = act
            if (st.enabled) scheduleDue(c, st, act)
        } catch (_: Throwable) {}
    }

    private fun duePi(c: Context) = PendingIntent.getBroadcast(c, 6, Intent(c, DueReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Volatile private var lastSkip = ""
    @Volatile private var lastDue = 0L
    @Volatile private var lastCancelWhy = ""

    private fun scheduleDue(c: Context, st: Store, act: Boolean) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!act || st.overlayUp) {
            am.cancel(duePi(c))
            val w = if (st.overlayUp) "window is up" else "screen off or locked"
            if (w != lastCancelWhy) { lastCancelWhy = w; lastDue = 0L; Log.i(c, "due alarm cancelled: $w") }
            return
        }
        lastCancelWhy = ""
        val rem = st.targetMin * 60000L - st.accMs
        val at = System.currentTimeMillis() + (if (rem <= 0) 60000L else rem + 500L)
        var how = "exact"
        try {
            if (rem > 0) {
                val show = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), duePi(c)); how = "alarm-clock"
            } else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, duePi(c))
        }
        catch (e: Throwable) { how = "inexact (" + e.javaClass.simpleName + ")"; try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, duePi(c)) } catch (_: Throwable) { how = "FAILED" } }
        if (Math.abs(at - lastDue) > 5000L) {
            lastDue = at
            Log.i(c, "due alarm armed $how for " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at)) + (if (rem <= 0) " (recheck, already past target)" else ""))
        }
    }

    fun scheduleWatchdog(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, 5, Intent(c, WatchdogReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = System.currentTimeMillis() + 8 * 60 * 1000L
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        catch (e: Throwable) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    /** prayer refresh + daily backup, no service or UI needed */
    fun maintain(c: Context) {
        val st = Store(c)
        try { if (st.prayerOn) { st.prayerTry = 0L; Prayer.ensureSync(st) } } catch (_: Throwable) {}
        try {
            val now = System.currentTimeMillis()
            if (st.autoBackup && st.refreshToken.isNotEmpty() && now - st.lastBackup >= 24 * 3600 * 1000L) {
                val e = Drive.backup(st); st.backupMsg = e ?: ""
            }
        } catch (_: Throwable) {}
    }
}

class MaintenanceJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Background.accrue(this, false)
        Log.i(this, "6h job ran")
        Background.startService(this)
        Thread { try { Background.maintain(this) } catch (_: Throwable) {} finally { jobFinished(p, false) } }.start()
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}

class DueReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Background.accrue(c, false)
        Log.i(c, "due alarm fired, acc=${Store(c).accMs / 1000}s of ${Store(c).targetMin * 60}s")
        Background.startService(c)
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Background.accrue(c, false)
        Log.i(c, "watchdog ran, acc=${Store(c).accMs / 1000}s")
        Background.startService(c)
        Background.scheduleWatchdog(c)
    }
}
