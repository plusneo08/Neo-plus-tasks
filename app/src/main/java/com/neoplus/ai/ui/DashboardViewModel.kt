package com.neoplus.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neoplus.ai.data.*
import com.neoplus.ai.calls.CallbackScheduler
import androidx.core.app.NotificationManagerCompat
import com.neoplus.ai.messaging.NeoNotificationListener
import com.neoplus.ai.messaging.Replier
import com.neoplus.ai.agent.AgentEngine
import com.neoplus.ai.integration.AppEntry
import com.neoplus.ai.integration.IntegrationManager
import com.neoplus.ai.security.KeystoreVault
import com.neoplus.ai.security.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class InboxMessage(
    val id: Long, val app: String, val sender: String, val text: String, val receivedAt: Long,
    val language: String, val urgency: String, val status: String, val draft: String?
)

data class TaskView(
    val id: Long, val tool: String, val status: String, val output: String?, val error: String?,
    val verified: Boolean, val attempts: Int, val waitingOn: Boolean, val priority: Int
)

data class RunView(val id: Long, val command: String, val note: String, val createdAt: Long, val tasks: List<TaskView>)

data class AwayState(val on: Boolean, val policy: String, val allowed: String, val owner: String, val since: Long)

data class Memory(val id: Long, val text: String, val createdAt: Long)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NeoDatabase.get(app)
    private val prefs = Prefs(app)
    private val integrations = IntegrationManager(app)

    val stopped = MutableStateFlow(prefs.emergencyStop)
    val accent = MutableStateFlow(prefs.accent)
    val hasApiKey = MutableStateFlow(prefs.hasApiKey())
    val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    private fun <T> Flow<T>.hot(init: T) =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)

    val model = MutableStateFlow(prefs.model)
    val away = MutableStateFlow(AwayState(prefs.awayOn, prefs.awayPolicy, prefs.allowedSenders, prefs.ownerName, prefs.awaySince))
    val listenerOn = MutableStateFlow(false)
    val planning = MutableStateFlow(false)
    val runs = combine(db.agentDao().runs(), db.agentDao().tasks()) { rs, ts ->
        rs.map { r ->
            RunView(r.id, runCatching { KeystoreVault.decrypt(r.encryptedCommand) }.getOrDefault("?"), r.note, r.createdAt,
                ts.filter { it.runId == r.id }.map {
                    TaskView(it.id, it.tool, it.status, it.encryptedOutput?.let { o -> runCatching { KeystoreVault.decrypt(o) }.getOrNull() },
                        it.error, it.verified, it.attempts, it.status == "QUEUED" && it.dependsOn.isNotBlank(), it.priority)
                })
        }
    }.flowOn(Dispatchers.Default).hot(emptyList())
    val messages = db.messageDao().all().map { list ->
        list.map {
            InboxMessage(it.id, it.appLabel, it.sender,
                runCatching { KeystoreVault.decrypt(it.encryptedText) }.getOrDefault("(unreadable)"),
                it.receivedAt, it.language, it.urgency, it.status,
                it.encryptedDraft?.let { d -> runCatching { KeystoreVault.decrypt(d) }.getOrNull() })
        }
    }.flowOn(Dispatchers.Default).hot(emptyList())
    val exactAlarms = MutableStateFlow(true)
    val callbacks = db.callbackDao().all().hot(emptyList())
    val logs = db.logDao().recent().hot(emptyList())
    val connections = db.connectionDao().all().hot(emptyList())
    val memories = db.memoryDao().all()
        .map { list ->
            list.map {
                Memory(it.id, runCatching { KeystoreVault.decrypt(it.encryptedText) }
                    .getOrDefault("(unreadable)"), it.createdAt)
            }
        }.flowOn(Dispatchers.Default).hot(emptyList())

    init { refreshApps() }

    private suspend fun log(source: String, msg: String, status: String = "OK") =
        db.logDao().insert(ActivityLog(timestamp = System.currentTimeMillis(), source = source, message = msg, status = status))

    fun refreshApps() = viewModelScope.launch(Dispatchers.IO) {
        apps.value = integrations.scan()
    }

    fun setEmergencyStop(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        prefs.emergencyStop = on
        val verified = Prefs(getApplication<Application>()).emergencyStop // re-read to verify
        stopped.value = verified
        if (verified) AgentEngine.cancelAll()
        log("Security", if (verified) "Emergency stop engaged" else "Agent resumed",
            if (verified == on) "OK" else "FAILED")
    }

    fun setAccent(argb: Int) { prefs.accent = argb; accent.value = prefs.accent }

    fun setConnected(entry: AppEntry, on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (stopped.value) { log("Apps", "Blocked: emergency stop is on", "BLOCKED"); return@launch }
        if (on && !entry.installed) { log("Apps", "${entry.spec.label} is not installed", "FAILED"); return@launch }
        val old = db.connectionDao().get(entry.spec.packageName)
        db.connectionDao().upsert(
            AppConnection(entry.spec.packageName, entry.spec.label, on,
                if (on) old?.monitoring ?: false else false, System.currentTimeMillis())
        )
        val saved = db.connectionDao().get(entry.spec.packageName)
        log("Apps", "${entry.spec.label} ${if (on) "connected" else "disconnected"}",
            if (saved?.connected == on) "OK" else "FAILED")
    }

    fun setMonitoring(entry: AppEntry, on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val c = db.connectionDao().get(entry.spec.packageName) ?: return@launch
        if (!c.connected) return@launch
        db.connectionDao().upsert(c.copy(monitoring = on, updatedAt = System.currentTimeMillis()))
        val saved = db.connectionDao().get(entry.spec.packageName)
        log("Apps", "Monitoring ${if (on) "on" else "off"} for ${entry.spec.label}",
            if (saved?.monitoring == on) "OK" else "FAILED")
    }

    fun openApp(entry: AppEntry) = viewModelScope.launch(Dispatchers.Main) {
        val ok = runCatching { integrations.open(entry.spec.packageName) }.getOrDefault(false)
        withLog("Apps", "Open ${entry.spec.label}", ok)
    }

    private fun withLog(src: String, msg: String, ok: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        log(src, msg, if (ok) "OK" else "FAILED")
    }

    fun addMemory(text: String) = viewModelScope.launch(Dispatchers.IO) {
        if (text.isBlank()) return@launch
        db.memoryDao().insert(MemoryItem(encryptedText = KeystoreVault.encrypt(text.trim()), createdAt = System.currentTimeMillis()))
        log("Memory", "Saved a memory")
    }

    fun deleteMemory(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        db.memoryDao().delete(id)
        log("Memory", "Deleted a memory", if (db.memoryDao().exists(id) == 0) "OK" else "FAILED")
    }

    fun saveApiKey(key: String) = viewModelScope.launch(Dispatchers.IO) {
        if (key.isBlank()) return@launch
        prefs.saveApiKey(key.trim())
        val ok = prefs.apiKey() == key.trim()
        hasApiKey.value = prefs.hasApiKey()
        log("Security", "API key stored encrypted", if (ok) "OK" else "FAILED")
    }

    fun clearApiKey() = viewModelScope.launch(Dispatchers.IO) {
        prefs.clearApiKey(); hasApiKey.value = prefs.hasApiKey()
        log("Security", "API key removed")
    }

    fun deleteAllData() = viewModelScope.launch(Dispatchers.IO) {
        db.callbackDao().open().forEach { CallbackScheduler.cancelAlarm(getApplication<Application>(), it.id) }
        AgentEngine.cancelAll()
        db.agentDao().clearTasks(); db.agentDao().clearRuns(); db.messageDao().clear(); db.callbackDao().clear(); db.memoryDao().clear(); db.connectionDao().clear(); db.logDao().clear()
        prefs.clearApiKey(); hasApiKey.value = false
        message.value = "All stored data deleted"
    }

    fun setModel(m: String) { prefs.model = m; model.value = prefs.model }

    fun refreshAlarmStatus() { exactAlarms.value = CallbackScheduler.canExact(getApplication<Application>()) }

    fun addCallback(at: Long, daily: Boolean, reason: String = "") = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        if (stopped.value) { message.value = "Emergency stop is on. Resume the agent first."; log("Calls", "Callback blocked: emergency stop", "BLOCKED"); return@launch }
        val id = db.callbackDao().insert(Callback(triggerAt = at, repeatDaily = daily, status = "PENDING",
            attempts = 0, maxAttempts = 3, retryMinutes = 2, reason = reason, updatedAt = System.currentTimeMillis()))
        val exact = CallbackScheduler.setAlarm(app, id, at)
        val saved = db.callbackDao().get(id)
        log("Calls", "Callback booked for ${DateFormat.getDateTimeInstance().format(Date(at))}" +
            (if (daily) ", daily" else "") + (if (exact) "" else " (approximate: exact alarms are off)"),
            if (saved?.status == "PENDING") "OK" else "FAILED")
        if (!exact) message.value = "Exact alarms are off, so the call may be a few minutes late."
    }

    fun cancelCallback(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        CallbackScheduler.dismissRing(app, id)
        CallbackScheduler.finish(app, id, "CANCELLED")
    }

    fun rescheduleCallback(id: Long, at: Long) = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        val cb = db.callbackDao().get(id) ?: return@launch
        if (stopped.value) { message.value = "Emergency stop is on."; return@launch }
        db.callbackDao().update(cb.copy(triggerAt = at, status = "PENDING", attempts = 0, updatedAt = System.currentTimeMillis()))
        CallbackScheduler.setAlarm(app, id, at)
        log("Calls", "Callback moved to ${DateFormat.getDateTimeInstance().format(Date(at))}",
            if (db.callbackDao().get(id)?.triggerAt == at) "OK" else "FAILED")
    }

    fun refreshListener() {
        listenerOn.value = NotificationManagerCompat.getEnabledListenerPackages(getApplication<Application>())
            .contains(getApplication<Application>().packageName)
    }

    fun refreshAway() { syncAway() }

    private fun syncAway() { away.value = AwayState(prefs.awayOn, prefs.awayPolicy, prefs.allowedSenders, prefs.ownerName, prefs.awaySince) }

    fun setAwayOn(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (on && stopped.value) { message.value = "Emergency stop is on. Resume the agent first."; return@launch }
        prefs.awayOn = on
        if (on) prefs.awaySince = System.currentTimeMillis()
        syncAway()
        log("Away Mode", if (on) "Away Mode on (${prefs.awayPolicy})" else "Away Mode off",
            if (prefs.awayOn == on) "OK" else "FAILED")
    }

    fun setAwayPolicy(p: String) { prefs.awayPolicy = p; syncAway() }
    fun setAllowed(list: String) { prefs.allowedSenders = list; syncAway() }
    fun setOwnerName(n: String) { prefs.ownerName = n; syncAway() }

    fun suggestReply(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val m = db.messageDao().get(id) ?: return@launch
        val text = runCatching { KeystoreVault.decrypt(m.encryptedText) }.getOrNull() ?: return@launch
        Replier.draft(prefs.apiKey(), prefs.model, prefs.ownerName, text).onSuccess {
            db.messageDao().update(m.copy(encryptedDraft = KeystoreVault.encrypt(it)))
        }.onFailure { message.value = it.message ?: "Could not draft a reply" }
    }

    /** Owner-approved reply. Hands text to the app; delivery is not confirmed by Android. */
    fun sendApproved(id: Long, text: String) = viewModelScope.launch(Dispatchers.IO) {
        if (stopped.value) { message.value = "Emergency stop is on."; return@launch }
        val m = db.messageDao().get(id) ?: return@launch
        val action = NeoNotificationListener.replyActions[id]
        if (action == null || text.isBlank()) {
            message.value = "Can't reply from here. The notification is gone; open ${m.appLabel} to reply."
            log("Inbox", "Reply to ${m.appLabel} not possible: notification gone", "FAILED"); return@launch
        }
        val ok = Replier.send(getApplication<Application>(), action, text.trim())
        if (ok) db.messageDao().update(m.copy(status = "SENT_APPROVED", repliedAt = System.currentTimeMillis(),
            encryptedDraft = KeystoreVault.encrypt(text.trim())))
        log("Inbox", if (ok) "Approved reply handed to ${m.appLabel}. Delivery isn't confirmed by Android."
        else "Could not hand reply to ${m.appLabel}", if (ok) "OK" else "FAILED")
    }

    fun dismissMessage(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val m = db.messageDao().get(id) ?: return@launch
        db.messageDao().update(m.copy(status = "DISMISSED"))
    }

    fun runCommand(text: String) = viewModelScope.launch(Dispatchers.IO) {
        if (text.isBlank()) return@launch
        planning.value = true
        val err = try { AgentEngine.submit(text.trim()) } finally { planning.value = false }
        if (err != null) message.value = err
    }
    fun approveTask(id: Long) = AgentEngine.approve(id)
    fun rejectTask(id: Long) = AgentEngine.reject(id)
    fun cancelTask(id: Long) = AgentEngine.cancelTask(id)

    fun clearMessage() { message.value = null }
}
