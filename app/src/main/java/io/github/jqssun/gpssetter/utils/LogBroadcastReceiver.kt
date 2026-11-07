package io.github.jqssun.gpssetter.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.Keep

@Keep
class LogBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AppLogger.ACTION_LOG_BATCH) {
            intent.extras?.let { AppLogger.handleIncomingLogBundle(it) }
        }
    }
}
