package com.neoplus.ai.agent

import android.content.Context
import com.neoplus.ai.calls.Ai
import com.neoplus.ai.security.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class PlannedTask(val localId: String, val tool: String, val args: JSONObject, val depends: List<String>, val priority: Int)
data class Plan(val reply: String, val tasks: List<PlannedTask>, val unsupported: List<String>)

object Planner {
    fun plan(ctx: Context, command: String): Result<Plan> {
        val prefs = Prefs(ctx)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", Locale.US).format(Date())
        val system = """
You are the planner of NEO, a personal assistant on the owner's Android phone.
Turn the owner's command into tasks using ONLY these tools:
${Tools.help()}

Rules:
- Current local time: $now (time zone ${TimeZone.getDefault().id}). Resolve words like "tomorrow" into exact values.
- A reminder ("remind me to X at T") is schedule_callback with reason X.
- Anything the tools cannot do (sending messages, making phone calls, reading email, controlling other apps) goes in "unsupported". Never pretend a tool can do it.
- Use depends_on only when one task needs another to finish first. priority 1 = most urgent, 3 = least.
- "reply" is one short sentence describing the plan. Do not claim anything has been done.
Return JSON only:
{"reply":"...","tasks":[{"id":"t1","tool":"...","args":{},"depends_on":[],"priority":2}],"unsupported":["..."]}
""".trimIndent()
        return Ai.reply(prefs.apiKey(), prefs.model, system, listOf("user" to command), json = true)
            .mapCatching { parsePlan(it, Tools.byName.keys) }
    }

    /** Pure parser, unit tested. Unknown tools become "unsupported" entries. */
    fun parsePlan(raw: String, valid: Set<String>): Plan {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = JSONObject(text)
        val tasks = mutableListOf<PlannedTask>()
        val unsupported = mutableListOf<String>()
        o.optJSONArray("unsupported")?.let { a -> for (i in 0 until a.length()) unsupported += a.optString(i) }
        o.optJSONArray("tasks")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                val tool = t.optString("tool")
                if (tool !in valid) { unsupported += "Unknown action: $tool"; continue }
                tasks += PlannedTask(
                    localId = t.optString("id", "t${i + 1}"), tool = tool,
                    args = t.optJSONObject("args") ?: JSONObject(),
                    depends = t.optJSONArray("depends_on").toStrings(),
                    priority = t.optInt("priority", 2).coerceIn(1, 3)
                )
            }
        }
        return Plan(o.optString("reply"), tasks.take(12), unsupported)
    }

    private fun JSONArray?.toStrings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
