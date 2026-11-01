package com.neoplus.ai.agent

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.neoplus.ai.calls.Ai
import com.neoplus.ai.calls.CallbackScheduler
import com.neoplus.ai.data.ActivityLog
import com.neoplus.ai.data.Callback
import com.neoplus.ai.data.MemoryItem
import com.neoplus.ai.data.NeoDatabase
import com.neoplus.ai.security.KeystoreVault
import com.neoplus.ai.security.Prefs
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** ok = the tool ran; verified = the tool re-checked its own result afterwards. */
data class ToolResult(val ok: Boolean, val output: String, val verified: Boolean = ok, val retryable: Boolean = false)

abstract class Tool(val name: String, val description: String, val argsHelp: String, val needsApproval: Boolean = false) {
    abstract suspend fun run(ctx: Context, args: JSONObject): ToolResult
}

object Tools {
    val all: List<Tool> = listOf(
        ListMessages, SummarizeMessages, CalendarEvents, ScheduleCallback, ListCallbacks, Remember, Recall, SetAwayMode
    )
    val byName: Map<String, Tool> = all.associateBy { it.name }
    fun help() = all.joinToString("\n") { "- ${it.name}(${it.argsHelp}): ${it.description}" }
}

private fun dec(s: String) = runCatching { KeystoreVault.decrypt(s) }.getOrDefault("?")

private object ListMessages : Tool("list_messages", "Lists recent messages from monitored apps and counts important ones.",
    "hours:int=24, only_important:bool=false") {
    override suspend fun run(ctx: Context, args: JSONObject): ToolResult {
        val hours = args.optInt("hours", 24).coerceIn(1, 168)
        val imp = args.optBoolean("only_important", false)
        val since = System.currentTimeMillis() - hours * 3_600_000L
        val list = NeoDatabase.get(ctx).messageDao().all().first()
            .filter { it.receivedAt >= since && (!imp || it.urgency != "NORMAL") }
        if (list.isEmpty()) return ToolResult(true, "No ${if (imp) "important " else ""}messages in the last $hours hours (monitored apps only).")
        val lines = list.take(15).joinToString("\n") {
            "• ${it.sender} (${it.appLabel}, ${it.urgency.lowercase()}): ${dec(it.encryptedText).take(70)}"
        }
        return ToolResult(true, "${list.size} message(s), ${list.count { it.urgency != "NORMAL" }} important:\n$lines")
    }
}

private object SummarizeMessages : Tool("summarize_messages", "Writes a short AI summary of recent monitored messages (sends their text to the AI service).",
    "hours:int=24") {
    override suspend fun run(ctx: Context, args: JSONObject): ToolResult {
        val hours = args.optInt("hours", 24).coerceIn(1, 168)
        val since = System.currentTimeMillis() - hours * 3_600_000L
        val list = NeoDatabase.get(ctx).messageDao().all().first().filter { it.receivedAt >= since }.take(30)
        if (list.isEmpty()) return ToolResult(true, "No messages to summarize in the last $hours hours.")
        val body = list.joinToString("\n") { "${it.sender} (${it.appLabel}): ${dec(it.encryptedText).take(200)}" }
        val prefs = Prefs(ctx)
        val r = Ai.reply(prefs.apiKey(), prefs.model,
            "Summarize these messages for their owner in at most 5 short bullet lines. Flag anything needing action. Do not invent details.",
            listOf("user" to body))
        return r.fold({ ToolResult(true, it) }, { ToolResult(false, it.message ?: "AI request failed", retryable = true) })
    }
}

private object CalendarEvents : Tool("calendar_events", "Lists calendar events for today or tomorrow.", "day:\"today\"|\"tomorrow\"") {
    override suspend fun run(ctx: Context, args: JSONObject): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "Calendar permission isn't granted. Allow it in More > Security.")
        val tomorrow = args.optString("day", "today").equals("tomorrow", true)
        val start = Calendar.getInstance().apply {
            if (tomorrow) add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start.timeInMillis); ContentUris.appendId(b, end.timeInMillis)
        val lines = mutableListOf<String>()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        ctx.contentResolver.query(b.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                lines += (if (allDay) "All day" else "${fmt.format(Date(c.getLong(1)))}–${fmt.format(Date(c.getLong(2)))}") +
                    "  ${c.getString(0) ?: "(no title)"}"
            }
        } ?: return ToolResult(false, "Android didn't return calendar data.")
        val label = if (tomorrow) "tomorrow" else "today"
        return ToolResult(true, if (lines.isEmpty()) "No events $label." else "${lines.size} event(s) $label:\n" + lines.joinToString("\n"))
    }
}

private object ScheduleCallback : Tool("schedule_callback",
    "Books an internet call from NEO to the owner (also used for reminders: NEO calls and says the reason).",
    "at:\"yyyy-MM-dd HH:mm\" OR in_minutes:int, daily:bool=false, reason:string") {
    override suspend fun run(ctx: Context, args: JSONObject): ToolResult {
        val now = System.currentTimeMillis()
        val at: Long = if (args.has("in_minutes")) now + args.optInt("in_minutes", 1).coerceAtLeast(1) * 60_000L
        else runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(args.optString("at"))!!.time }
            .getOrNull() ?: return ToolResult(false, "I couldn't understand the time for the call.")
        if (at <= now) return ToolResult(false, "That time has already passed.")
        val dao = NeoDatabase.get(ctx).callbackDao()
        val id = dao.insert(Callback(triggerAt = at, repeatDaily = args.optBoolean("daily", false), status = "PENDING",
            attempts = 0, maxAttempts = 3, retryMinutes = 2, reason = args.optString("reason", ""), updatedAt = now))
        val exact = CallbackScheduler.setAlarm(ctx, id, at)
        val saved = dao.get(id)
        val ok = saved != null && saved.status == "PENDING" && saved.triggerAt == at
        return ToolResult(ok, (if (ok) "Call booked for ${DateFormat.getDateTimeInstance().format(Date(at))}" else "Could not save the call") +
            (if (exact) "." else " (approximate: exact alarms are off)."), verified = ok)
    }
}

private object ListCallbacks : Tool("list_callbacks", "Lists upcoming booked calls.", "") {
    override suspend fun run(ctx: Context, args: JSONObject): ToolResult {
        val list = NeoDatabase.get(ctx).callbackDao().open()
        if (list.isEmpty()) return ToolResult(true, "No calls booked.")
        return ToolResult(true, list.sortedBy { it.triggerAt }.joinToString("\n") {
            "• ${DateFormat.getDateTimeInstance().format(Date(it.triggerAt))}${if (it.repeatDaily) " (daily)" else ""}" +
                (if (it.reason.isNotBlank()) " – ${it.reason}" else "")
        })
    }
}

private object Remember : Tool("remember", "Saves a note to the owner's memory.", "text:string") {
    override suspend fun run(ctx: Context, args: JSONObject): ToolResult {
        val text = args.optString("text").trim()
        if (text.isEmpty()) return ToolResult(false, "Nothing to remember.")
        val dao = NeoDatabase.get(ctx).memoryDao()
        val id = dao.insert(MemoryItem(encryptedText = KeystoreVault.encrypt(text), createdAt = System.currentTimeMillis()))
        val ok = dao.exists(id) == 1
        return ToolResult(ok, if (ok) "Saved to memory: $text" else "Could not save to memory", verified = ok)
    }
}

private object Recall : Tool("recall", "Searches the owner's memory.", "query:string") {
    override suspend fun run(ctx: Context, args: JSONObject): ToolResult {
        val words = args.optString("query").lowercase().split(' ').filter { it.length > 2 }
        val hits = NeoDatabase.get(ctx).memoryDao().all().first().map { dec(it.encryptedText) }
            .filter { m -> words.isEmpty() || words.any { m.lowercase().contains(it) } }
        return ToolResult(true, if (hits.isEmpty()) "Nothing in memory matches." else hits.take(10).joinToString("\n") { "• $it" })
    }
}

private object SetAwayMode : Tool("set_away_mode", "Turns Away Mode on or off (needs the owner's approval).",
    "on:bool, policy:\"READ\"|\"SUGGEST\"|\"AUTO\" optional", needsApproval = true) {
    override suspend fun run(ctx: Context, args: JSONObject): ToolResult {
        val prefs = Prefs(ctx)
        val on = args.optBoolean("on", true)
        args.optString("policy").takeIf { it in setOf("READ", "SUGGEST", "AUTO") }?.let { prefs.awayPolicy = it }
        prefs.awayOn = on
        if (on) prefs.awaySince = System.currentTimeMillis()
        val ok = Prefs(ctx).awayOn == on
        NeoDatabase.get(ctx).logDao().insert(ActivityLog(timestamp = System.currentTimeMillis(), source = "Agent",
            message = "Away Mode ${if (on) "on" else "off"} (${prefs.awayPolicy})", status = if (ok) "OK" else "FAILED"))
        return ToolResult(ok, "Away Mode is ${if (on) "on (${prefs.awayPolicy.lowercase()})" else "off"}.", verified = ok)
    }
}
