package com.neoplus.ai.messaging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.neoplus.ai.MainActivity
import com.neoplus.ai.calls.Ai

/** Keyword-based guess. Good enough to pick a reply language, not a real detector. */
object LangDetect {
    private val words = mapOf(
        "sw" to listOf("habari", "asante", "sawa", "karibu", "pole", "tafadhali", "nipigie", "kesho", "rafiki", "mimi", "wewe", "sijui", "nimefika"),
        "ny" to listOf("muli bwanji", "zikomo", "chonde", "ndikufuna", "bwanji", "ndili", "inde", "ayi", "mawa", "lero", "mwadzuka", "pepani", "ndipo", "ndalama", "tikuonana"),
        "fr" to listOf("bonjour", "merci", "salut", "demain", "aujourd", "comment", "vous", "je suis", "s'il", "bonsoir"),
        "pt" to listOf("olá", "obrigado", "obrigada", "você", "amanhã", "por favor", "bom dia", "tudo bem", "não", "boa tarde"),
        "en" to listOf("the", "and", "please", "hello", "thanks", "you", "call", "when", "where", "can")
    )

    fun guess(text: String): String {
        val t = " ${text.lowercase()} "
        val best = words.map { (code, list) -> code to list.count { w -> t.contains(if (w.contains(' ')) w else " $w") } }
            .maxByOrNull { it.second }
        return if (best == null || best.second == 0) "?" else best.first
    }
}

/** Keyword triage. HIGH = urgent, SENSITIVE = money/legal/security, otherwise NORMAL. */
object Triage {
    private val high = listOf("urgent", "emergency", "asap", "immediately", "hospital", "accident", "died", "death",
        "haraka", "dharura", "msaada", "mwadzidzidzi", "urgence", "au secours", "urgente", "emergência", "socorro")
    private val sensitive = listOf("pay ", "payment", "send money", "loan", "bank", "account number", "lawyer", "court", "police",
        "otp", "password", "verification code", "pin ", "malipo", "fedha", "pesa", "ndalama", "argent", "paiement", "avocat",
        "tribunal", "dinheiro", "pagamento", "advogado")

    fun rate(text: String): String {
        val t = " ${text.lowercase()} "
        if (high.any { t.contains(it) }) return "HIGH"
        if (sensitive.any { t.contains(it) }) return "SENSITIVE"
        return "NORMAL"
    }

    /** Empty list means nobody is allowed. */
    fun allowed(list: String, sender: String): Boolean {
        val names = list.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return names.contains(sender.trim().lowercase())
    }
}

object Replier {
    /** Hands text to the app's own reply action. Android gives no delivery receipt. */
    fun send(ctx: Context, action: Notification.Action, text: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val first = inputs.firstOrNull() ?: return false
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = Bundle().apply { putCharSequence(first.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try { action.actionIntent.send(ctx, 0, intent); true }
        catch (e: PendingIntent.CanceledException) { false }
    }

    fun draft(apiKey: String?, model: String, owner: String, incoming: String): Result<String> {
        val who = owner.ifBlank { "the owner" }
        val system = "You are NEO, an AI assistant replying to a message on behalf of $who, who is unavailable. " +
            "Say you are an AI assistant. Reply in the language of the incoming message, in one or two short sentences. " +
            "Never make commitments, agree to payments, share personal details or promise a time. " +
            "Say $who will reply when available."
        return Ai.reply(apiKey, model, system, listOf("user" to incoming)).map {
            if (it.contains("AI assistant", true) || it.contains("NEO", true)) it
            else "NEO, AI assistant of $who: $it"
        }
    }
}

object Alerts {
    private const val CHANNEL = "neo_alerts"

    /** Tells the owner about an important message. Returns true only if Android shows it. */
    fun notifyOwner(ctx: Context, id: Long, appLabel: String, urgency: String): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Important messages", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, id.toInt(), Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (urgency == "HIGH") "Urgent message in $appLabel" else "Sensitive message in $appLabel")
            .setContentText("NEO did not reply. Open NEO PLUS AI to review.")
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(("alert$id").hashCode(), n)
        return nm.activeNotifications.any { it.id == ("alert$id").hashCode() }
    }
}
