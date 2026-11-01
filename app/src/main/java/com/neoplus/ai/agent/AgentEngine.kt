package com.neoplus.ai.agent

import android.content.Context
import com.neoplus.ai.data.ActivityLog
import com.neoplus.ai.data.AgentRun
import com.neoplus.ai.data.AgentTask
import com.neoplus.ai.data.NeoDatabase
import com.neoplus.ai.security.KeystoreVault
import com.neoplus.ai.security.Prefs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Plans a command into tasks and runs them: priority order, up to 3 in parallel,
 * dependencies, approvals, retries, cancellation. Runs while the app process is alive.
 */
object AgentEngine {
    private var app: Context? = null
    private val ctx get() = app!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Semaphore(3)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val tickLock = Mutex()
    private val done = setOf("DONE", "UNVERIFIED")

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        scope.launch { NeoDatabase.get(ctx).agentDao().markInterrupted() }
    }

    private suspend fun log(msg: String, status: String = "OK") =
        NeoDatabase.get(ctx).logDao().insert(ActivityLog(timestamp = System.currentTimeMillis(), source = "Agent", message = msg, status = status))

    /** Returns an error message, or null when tasks were queued. */
    suspend fun submit(command: String): String? {
        val prefs = Prefs(ctx)
        if (prefs.emergencyStop) return "Emergency stop is on. Resume the agent first."
        if (!prefs.hasApiKey()) return "Add your AI key in More > Security first."
        val dao = NeoDatabase.get(ctx).agentDao()
        val now = System.currentTimeMillis()
        val run = AgentRun(encryptedCommand = KeystoreVault.encrypt(command), createdAt = now, note = "Planning…")
        val runId = dao.insertRun(run)
        val plan = Planner.plan(ctx, command).getOrElse {
            val msg = "Could not plan: ${it.message ?: "unknown error"}"
            dao.updateRun(run.copy(id = runId, note = msg)); log(msg, "FAILED"); return msg
        }
        val idMap = mutableMapOf<String, Long>()
        for (t in plan.tasks) {
            val tool = Tools.byName.getValue(t.tool)
            val deps = t.depends.mapNotNull { idMap[it] }.joinToString(",")
            idMap[t.localId] = dao.insertTask(AgentTask(runId = runId, tool = t.tool, argsJson = t.args.toString(),
                dependsOn = deps, priority = t.priority, status = if (tool.needsApproval) "AWAITING_APPROVAL" else "QUEUED",
                attempts = 0, encryptedOutput = null, verified = false, error = null, createdAt = now, finishedAt = null))
        }
        plan.unsupported.forEach {
            dao.insertTask(AgentTask(runId = runId, tool = "(not supported)", argsJson = "{}", dependsOn = "", priority = 3,
                status = "FAILED", attempts = 0, encryptedOutput = null, verified = false,
                error = "NEO can't do this yet: $it", createdAt = now, finishedAt = now))
        }
        dao.updateRun(run.copy(id = runId, note = plan.reply.ifBlank { "Plan ready." }))
        log("Planned ${plan.tasks.size} task(s), ${plan.unsupported.size} not supported")
        pump()
        return null
    }

    fun pump() { scope.launch { tick() } }

    private suspend fun tick() = tickLock.withLock {
        if (Prefs(ctx).emergencyStop) { cancelAllInternal(); return@withLock }
        val dao = NeoDatabase.get(ctx).agentDao()
        for (t in dao.queued()) {
            if (jobs.containsKey(t.id)) continue
            val states = t.dependsOn.split(',').mapNotNull { it.toLongOrNull() }.map { dao.getTask(it)?.status ?: "FAILED" }
            if (states.any { it == "FAILED" || it == "CANCELLED" }) {
                dao.updateTask(t.copy(status = "FAILED", error = "A step it depends on did not finish", finishedAt = System.currentTimeMillis()))
                continue
            }
            if (!states.all { it in done }) continue // still waiting
            jobs[t.id] = scope.launch { runTask(t.id) }
        }
    }

    private suspend fun runTask(id: Long) {
        val dao = NeoDatabase.get(ctx).agentDao()
        try {
            gate.withPermit {
                var t = dao.getTask(id) ?: return
                if (t.status != "QUEUED") return
                t = t.copy(status = "RUNNING"); dao.updateTask(t)
                val tool = Tools.byName[t.tool]
                if (tool == null) { finish(t, ToolResult(false, "Unknown tool")); return }
                val args = runCatching { JSONObject(t.argsJson) }.getOrDefault(JSONObject())
                var attempt = 0
                var res = ToolResult(false, "")
                while (true) {
                    attempt++
                    res = try {
                        withTimeoutOrNull(60_000) { tool.run(ctx, args) } ?: ToolResult(false, "Timed out", retryable = true)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { ToolResult(false, e.message ?: "Unexpected error") }
                    if (!res.ok && res.retryable && attempt < 3) delay(2000L * attempt) else break
                }
                finish(t.copy(attempts = attempt), res)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.getTask(id)?.let {
                    if (it.status == "RUNNING" || it.status == "QUEUED")
                        dao.updateTask(it.copy(status = "CANCELLED", error = "Cancelled", finishedAt = System.currentTimeMillis()))
                }
            }
            throw e
        } finally {
            jobs.remove(id)
            pump()
        }
    }

    private suspend fun finish(t: AgentTask, r: ToolResult) {
        val dao = NeoDatabase.get(ctx).agentDao()
        val status = when { !r.ok -> "FAILED"; r.verified -> "DONE"; else -> "UNVERIFIED" }
        dao.updateTask(t.copy(status = status, verified = r.ok && r.verified,
            encryptedOutput = if (r.ok) KeystoreVault.encrypt(r.output) else null,
            error = if (r.ok) null else r.output, finishedAt = System.currentTimeMillis()))
        val saved = dao.getTask(t.id)?.status
        log("${t.tool}: ${status.lowercase()}", if (status == "FAILED" || saved != status) "FAILED" else "OK")
    }

    fun cancelTask(id: Long) { scope.launch {
        val job = jobs[id]
        if (job != null) job.cancel() else {
            val dao = NeoDatabase.get(ctx).agentDao()
            dao.getTask(id)?.let { if (it.status == "QUEUED" || it.status == "AWAITING_APPROVAL")
                dao.updateTask(it.copy(status = "CANCELLED", error = "Cancelled", finishedAt = System.currentTimeMillis())) }
            pump()
        }
    } }

    fun approve(id: Long) { scope.launch {
        if (Prefs(ctx).emergencyStop) return@launch
        val dao = NeoDatabase.get(ctx).agentDao()
        dao.getTask(id)?.takeIf { it.status == "AWAITING_APPROVAL" }?.let {
            dao.updateTask(it.copy(status = "QUEUED")); log("Approved ${it.tool}")
        }
        pump()
    } }

    fun reject(id: Long) { scope.launch {
        val dao = NeoDatabase.get(ctx).agentDao()
        dao.getTask(id)?.takeIf { it.status == "AWAITING_APPROVAL" }?.let {
            dao.updateTask(it.copy(status = "CANCELLED", error = "Rejected by you", finishedAt = System.currentTimeMillis()))
            log("Rejected ${it.tool}")
        }
    } }

    fun cancelAll() { scope.launch { cancelAllInternal() } }

    private suspend fun cancelAllInternal() {
        jobs.values.forEach { it.cancel() }
        NeoDatabase.get(ctx).agentDao().cancelOpen()
    }
}
