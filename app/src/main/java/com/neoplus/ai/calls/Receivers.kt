package com.neoplus.ai.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun BroadcastReceiver.async(ctx: Context, block: suspend (Context) -> Unit) {
    val pending = goAsync()
    val app = ctx.applicationContext
    CoroutineScope(Dispatchers.IO).launch {
        try { block(app) } finally { pending.finish() }
    }
}

class CallbackReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(CallbackScheduler.EXTRA_ID, -1)
        if (id < 0) return
        async(ctx) { app ->
            when (intent.action) {
                CallbackScheduler.ACTION_FIRE -> CallbackScheduler.fire(app, id)
                CallbackScheduler.ACTION_DECLINE -> {
                    CallbackScheduler.dismissRing(app, id)
                    CallbackScheduler.finish(app, id, "DECLINED")
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            async(ctx) { CallbackScheduler.restore(it) }
        }
    }
}
