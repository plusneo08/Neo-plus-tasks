package com.neoplus.ai.messaging

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.neoplus.ai.data.ActivityLog
import com.neoplus.ai.data.Message
import com.neoplus.ai.data.NeoDatabase
import com.neoplus.ai.security.KeystoreVault
import com.neoplus.ai.security.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Reads notifications only from apps the owner connected AND allowed monitoring for. */
class NeoNotificationListener : NotificationListenerService() {
    companion object {
        /** Reply actions of live notifications, by message id. Gone once the notification is gone. */
        val replyActions = ConcurrentHashMap<Long, Notification.Action>()
        private const val DUPLICATE_WINDOW = 10 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        scope.launch { runCatching { handle(sbn) } }
    }

    private suspend fun log(msg: String, status: String = "OK") =
        NeoDatabase.get(applicationContext).logDao().insert(
            ActivityLog(timestamp = System.currentTimeMillis(), source = "Inbox", message = msg, status = status)
        )

    private suspend fun handle(sbn: StatusBarNotification) {
        val app = applicationContext
        val db = NeoDatabase.get(app)
        val prefs = Prefs(app)
        if (prefs.emergencyStop) return
        val conn = db.connectionDao().get(sbn.packageName) ?: return
        if (!conn.connected || !conn.monitoring) return

        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras
        var sender = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        @Suppress("DEPRECATION")
        (ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle)?.let { b ->
            b.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }?.let { text = it }
            b.getCharSequence("sender")?.toString()?.takeIf { it.isNotBlank() }?.let { sender = it }
        }
        if (sender.isBlank() || text.isBlank()) return

        val key = "${sbn.packageName}|$sender|${text.hashCode()}|${n.`when`}"
        if (db.messageDao().count(key) > 0) return // same message re-posted

        val lang = LangDetect.guess(text)
        val urgency = Triage.rate(text)
        val id = db.messageDao().insert(
            Message(packageName = sbn.packageName, appLabel = conn.label, sender = sender,
                encryptedText = KeystoreVault.encrypt(text), receivedAt = System.currentTimeMillis(),
                language = lang, urgency = urgency, status = if (urgency == "NORMAL") "NEW" else "ESCALATED",
                dedupeKey = key)
        )
        val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (action != null) replyActions[id] = action
        log("Message received in ${conn.label} ($urgency)")

        if (urgency != "NORMAL") {
            val shown = Alerts.notifyOwner(app, id, conn.label, urgency)
            log("Owner alert for $urgency message ${if (shown) "shown" else "could not be shown"}", if (shown) "OK" else "FAILED")
            return // sensitive or urgent: never auto-reply
        }
        if (!prefs.awayOn || prefs.awayPolicy == "READ") return

        val key2 = prefs.apiKey()
        val auto = prefs.awayPolicy == "AUTO" && action != null && Triage.allowed(prefs.allowedSenders, sender)
        if (key2 == null) { if (auto) log("Auto reply skipped: no API key", "FAILED"); return }
        if (auto) {
            val last = db.messageDao().lastReply(sbn.packageName, sender)
            if (last != null && System.currentTimeMillis() - last < DUPLICATE_WINDOW) {
                log("Auto reply skipped: already replied to this sender recently"); return
            }
        }
        Replier.draft(key2, prefs.model, prefs.ownerName, text).onSuccess { reply ->
            val cur = db.messageDao().get(id) ?: return@onSuccess
            if (auto && action != null && Replier.send(app, action, reply)) {
                db.messageDao().update(cur.copy(status = "REPLIED_AUTO", repliedAt = System.currentTimeMillis(),
                    encryptedDraft = KeystoreVault.encrypt(reply)))
                log("Reply handed to ${conn.label}. Delivery isn't confirmed by Android.")
            } else {
                db.messageDao().update(cur.copy(encryptedDraft = KeystoreVault.encrypt(reply)))
                if (auto) log("Could not hand the reply to ${conn.label}", "FAILED")
            }
        }.onFailure { log("Draft failed: ${it.message}", "FAILED") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { /* keep actions; they die with the notification */ }
}
