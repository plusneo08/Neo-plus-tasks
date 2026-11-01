package com.neoplus.ai.calls

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.neoplus.ai.data.ActivityLog
import com.neoplus.ai.data.Callback
import com.neoplus.ai.data.NeoDatabase
import com.neoplus.ai.security.Prefs

/**
 * Internet callbacks: NEO "rings" the owner inside the app (full-screen call notification),
 * then talks over an in-app voice session. No SIM or phone number is involved.
 */
object CallbackScheduler {
    const val ACTION_FIRE = "com.neoplus.ai.CALLBACK_FIRE"
    const val ACTION_DECLINE = "com.neoplus.ai.CALLBACK_DECLINE"
    const val EXTRA_ID = "id"
    const val EXTRA_ANSWER = "answer"
    private const val CHANNEL = "neo_calls"
    private const val DAY = 24L * 60 * 60 * 1000
    private val TERMINAL = setOf("CANCELLED", "ANSWERED", "DECLINED", "MISSED", "BLOCKED", "FAILED")

    /** Next daily occurrence strictly after [now]. */
    fun nextDaily(from: Long, now: Long): Long {
        var t = from + DAY
        while (t <= now) t += DAY
        return t
    }

    private fun alarmIntent(ctx: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.toInt(),
        Intent(ctx, CallbackReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** Returns true if an exact alarm was set, false if only an approximate one was possible. */
    fun setAlarm(ctx: Context, id: Long, at: Long): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = canExact(ctx)
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx, id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx, id))
        return exact
    }

    fun cancelAlarm(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx, id))
    }

    suspend fun log(ctx: Context, msg: String, status: String = "OK") =
        NeoDatabase.get(ctx).logDao().insert(
            ActivityLog(timestamp = System.currentTimeMillis(), source = "Calls", message = msg, status = status)
        )

    /** Ends a callback with a final status and, for daily callbacks, books tomorrow's. */
    suspend fun finish(ctx: Context, id: Long, status: String) {
        val dao = NeoDatabase.get(ctx).callbackDao()
        val cb = dao.get(id) ?: return
        val now = System.currentTimeMillis()
        dao.update(cb.copy(status = status, updatedAt = now))
        cancelAlarm(ctx, id)
        val saved = dao.get(id)?.status
        log(ctx, "Callback ${status.lowercase()}", if (saved == status) "OK" else "FAILED")
        if (cb.repeatDaily && status != "CANCELLED") {
            val next = nextDaily(cb.triggerAt, now)
            val nid = dao.insert(cb.copy(id = 0, triggerAt = next, status = "PENDING", attempts = 0, updatedAt = now))
            setAlarm(ctx, nid, next)
            log(ctx, "Daily callback booked for tomorrow")
        }
    }

    /** Called by the alarm. First ring, retry ring, or final missed check. */
    suspend fun fire(ctx: Context, id: Long) {
        val db = NeoDatabase.get(ctx)
        val cb = db.callbackDao().get(id) ?: return
        if (cb.status in TERMINAL) return
        if (cb.status == "RINGING") {
            // Still ringing at the next alarm: the previous ring went unanswered.
            log(ctx, "Callback attempt ${cb.attempts} not answered", "FAILED")
            if (cb.attempts >= cb.maxAttempts) { finish(ctx, id, "MISSED"); return }
        }
        if (Prefs(ctx).emergencyStop) { finish(ctx, id, "BLOCKED"); return }
        val n = cb.attempts + 1
        db.callbackDao().update(cb.copy(status = "RINGING", attempts = n, updatedAt = System.currentTimeMillis()))
        val shown = ring(ctx, id)
        if (!shown) {
            log(ctx, "Could not show the call: notifications are off", "FAILED")
            finish(ctx, id, "FAILED"); return
        }
        log(ctx, "NEO is calling (attempt $n of ${cb.maxAttempts})")
        setAlarm(ctx, id, System.currentTimeMillis() + cb.retryMinutes * 60_000L)
    }

    /** Re-books alarms after a reboot or app update. */
    suspend fun restore(ctx: Context) {
        val now = System.currentTimeMillis()
        NeoDatabase.get(ctx).callbackDao().open().forEach { cb ->
            val at = if (cb.status == "RINGING" || cb.triggerAt <= now) now + 2000 else cb.triggerAt
            setAlarm(ctx, cb.id, at)
        }
        log(ctx, "Callbacks restored after restart")
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "NEO calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "NEO calling you"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /** Posts the call and checks Android actually shows it. */
    private fun ring(ctx: Context, id: Long): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        ensureChannel(ctx)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun activity(answer: Boolean, code: Int) = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, CallActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_ANSWER, answer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val decline = PendingIntent.getBroadcast(
            ctx, id.toInt() + 200000,
            Intent(ctx, CallbackReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_ID, id), flags
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("NEO is calling")
            .setContentText("Tap to answer")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setTimeoutAfter(90_000)
            .setContentIntent(activity(false, id.toInt() + 100000))
            .setFullScreenIntent(activity(false, id.toInt() + 300000), true)
            .addAction(android.R.drawable.sym_action_call, "Answer", activity(true, id.toInt() + 400000))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
            .build()
        nm.notify(id.toInt(), n)
        return nm.activeNotifications.any { it.id == id.toInt() }
    }

    fun dismissRing(ctx: Context, id: Long) =
        ctx.getSystemService(NotificationManager::class.java).cancel(id.toInt())
}
