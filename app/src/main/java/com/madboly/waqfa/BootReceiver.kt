package com.madboly.waqfa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Log.i(c, "receiver: " + (i.action ?: "?"))
        Background.scheduleAll(c)
        Background.startService(c)
    }
}
