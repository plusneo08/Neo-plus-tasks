package com.neoplus.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neoplus.ai.messaging.NeoNotificationListener
import java.text.DateFormat
import java.util.Date

private val policies = listOf("READ" to "Read only", "SUGGEST" to "Suggest replies", "AUTO" to "Auto reply")
private val langNames = mapOf("en" to "English", "sw" to "Swahili", "ny" to "Chichewa", "fr" to "French", "pt" to "Portuguese", "?" to "Unknown")

@Composable
internal fun InboxScreen(vm: DashboardViewModel) {
    val ctx = LocalContext.current
    val msgs by vm.messages.collectAsStateWithLifecycle()
    val away by vm.away.collectAsStateWithLifecycle()
    val access by vm.listenerOn.collectAsStateWithLifecycle()
    val stopped by vm.stopped.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshListener(); vm.refreshAway() }

    val since = if (away.on) away.since else System.currentTimeMillis() - 24 * 3_600_000L
    val recent = msgs.filter { it.receivedAt >= since }
    var owner by remember(away.owner) { mutableStateOf(away.owner) }
    var allowed by remember(away.allowed) { mutableStateOf(away.allowed) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Inbox", "Messages from apps you connected and allowed monitoring for.") }
        item {
            Panel {
                Text("Notification access", fontWeight = FontWeight.Medium)
                Text(if (access) "On. NEO can read notifications from monitored apps only." else "Off. NEO can't see any messages yet.",
                    color = if (access) Good else Bad, fontSize = 13.sp)
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.padding(top = 8.dp)) { Text(if (access) "Manage access" else "Turn on access") }
                if (!access) Text("If the switch is greyed out: Android Settings > Apps > NEO PLUS AI > ⋮ > Allow restricted settings.",
                    color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Panel {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Away Mode", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Switch(checked = away.on, onCheckedChange = { vm.setAwayOn(it) })
                }
                Text("What NEO does with new messages while you're away:", color = TextLo, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    policies.forEach { (code, label) ->
                        FilterChip(selected = away.policy == code, onClick = { vm.setAwayPolicy(code) }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(owner, { owner = it; vm.setOwnerName(it) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Your name (used in replies)") }, singleLine = true)
                if (away.policy == "AUTO") OutlinedTextField(allowed, { allowed = it; vm.setAllowed(it) },
                    Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Senders NEO may answer (names, comma separated)") })
                Text("Replies always say they come from an AI assistant. Urgent or sensitive messages (money, legal, codes) are never auto-answered; you get an alert instead. " +
                    "In Suggest and Auto modes, message text is sent to the AI service to write the reply.",
                    color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            Panel {
                Text(if (away.on) "Away report" else "Last 24 hours", fontWeight = FontWeight.Medium)
                Text("Received: ${recent.size}  ·  Important: ${recent.count { it.urgency != "NORMAL" }}\n" +
                    "Answered by NEO: ${recent.count { it.status == "REPLIED_AUTO" }}  ·  Sent by you: ${recent.count { it.status == "SENT_APPROVED" }}\n" +
                    "Waiting for you: ${recent.count { it.status == "NEW" || it.status == "ESCALATED" }}",
                    fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (msgs.isEmpty()) item { Text("No messages yet. Connect an app, switch on monitoring, and turn on notification access.", color = TextLo) }
        items(msgs.filter { it.status != "DISMISSED" }, key = { it.id }) { m ->
            var draft by remember(m.id, m.draft) { mutableStateOf(m.draft.orEmpty()) }
            val canReply = NeoNotificationListener.replyActions.containsKey(m.id)
            Panel {
                Text("${m.sender} · ${m.app}", fontWeight = FontWeight.Medium)
                Text("${langNames[m.language] ?: "Unknown"} (guess) · ${m.urgency.lowercase()} · ${m.status.lowercase().replace('_', ' ')} · " +
                    DateFormat.getTimeInstance().format(Date(m.receivedAt)),
                    color = if (m.urgency == "NORMAL") TextLo else Bad, fontSize = 11.sp)
                Text(m.text, modifier = Modifier.padding(top = 6.dp))
                if (m.status == "NEW" || m.status == "ESCALATED") {
                    OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Reply") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { vm.suggestReply(m.id) }) { Text("Suggest") }
                        TextButton(onClick = { copy(ctx, draft) }, enabled = draft.isNotBlank()) { Text("Copy") }
                        TextButton(onClick = { vm.sendApproved(m.id, draft) },
                            enabled = canReply && draft.isNotBlank() && !stopped) { Text("Send") }
                        TextButton(onClick = { vm.dismissMessage(m.id) }) { Text("Dismiss") }
                    }
                    if (!canReply) Text("Send is off: this app's notification has no reply button anymore.", color = TextLo, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("NEO reply", text))
}
