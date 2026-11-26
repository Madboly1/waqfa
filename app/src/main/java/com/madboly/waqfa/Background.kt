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
        try { c.startForegroundService(Intent(c, WaqfaService::class.java)) } catch (e: Throwable) { Store(c).lastErr = "start: " + e.javaClass.simpleName }
    }

    fun scheduleAll(c: Context) {
        val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.getPendingJob(77) == null) {
            js.schedule(JobInfo.Builder(77, ComponentName(c, MaintenanceJob::class.java))
                .setPeriodic(6 * 3600 * 1000L).setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        }
        scheduleWatchdog(c)
    }

    fun scheduleWatchdog(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, 5, Intent(c, WatchdogReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = System.currentTimeMillis() + 15 * 60 * 1000L
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
        Background.startService(this)
        Thread { try { Background.maintain(this) } finally { jobFinished(p, false) } }.start()
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Background.startService(c)
        Background.scheduleWatchdog(c)
    }
}
