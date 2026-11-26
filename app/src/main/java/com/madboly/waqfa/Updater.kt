package com.madboly.waqfa

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    // Version file for the in-app update channel. The channel is not live until the update repo is published.
    const val VERSION_URL = "https://raw.githubusercontent.com/Madboly1/waqfa-updates/main/version.json"

    class Info(val code: Int, val name: String, val url: String)

    fun check(): Info? {
        val c = URL(VERSION_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        if (c.responseCode != 200) return null
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        return Info(j.getInt("versionCode"), j.optString("versionName"), j.getString("apkUrl"))
    }

    fun install(ctx: Context, url: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000; c.instanceFollowRedirects = true
        val pi = ctx.packageManager.packageInstaller
        val sid = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val s = pi.openSession(sid)
        s.openWrite("waqfa.apk", 0, -1).use { out ->
            c.inputStream.use { it.copyTo(out) }
            s.fsync(out)
        }
        val pend = PendingIntent.getBroadcast(ctx, sid, Intent(ctx, InstallReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        s.commit(pend.intentSender)
        s.close()
    }
}
