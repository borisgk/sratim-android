package com.ru9n.sratim.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TvInitializeProgramsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("TvHomeChannel", "Received broadcast: $action")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TvHomeChannelPublisher.fetchAndPublish(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
