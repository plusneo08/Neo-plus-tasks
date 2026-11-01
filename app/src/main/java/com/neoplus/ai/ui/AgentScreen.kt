package com.neoplus.ai.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Warn = Color(0xFFFFB347)

private fun statusColor(s: String, accent: Color) = when (s) {
    "DONE" -> Good
    "UNVERIFIED", "AWAITING_APPROVAL" -> Warn
    "FAILED", "CANCELLED" -> Bad
    "RUNNING" -> accent
    else -> TextLo
}

private fun statusLabel(t: TaskView) = when {
    t.status == "QUEUED" && t.waitingOn -> "waiting"
    t.status == "AWAITING_APPROVAL" -> "needs your approval"
    t.status == "UNVERIFIED" -> "ran, not verified"
    t.status == "DONE" -> "done, verified"
    else -> t.status.lowercase()
}

@Composable
internal fun AgentScreen(vm: DashboardViewModel) {
    val ctx = LocalContext.current
    val runs by vm.runs.collectAsStateWithLifecycle()
    val planning by vm.planning.collectAsStateWithLifecycle()
    val stopped by vm.stopped.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    var text by remember { mutableStateOf("") }
    val all = runs.flatMap { it.tasks }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Agent", "Tell NEO what to do. It plans, runs and checks each step.") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("e.g. Remind me to call John tomorrow at 9") })
                IconButton(onClick = {
                    try {
                        mic.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                    } catch (e: ActivityNotFoundException) { }
                }) { Icon(Icons.Filled.Mic, "Speak a command") }
            }
            Button(onClick = { vm.runCommand(text); text = "" }, enabled = text.isNotBlank() && !planning && !stopped,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (planning) "Planning…" else if (stopped) "Stopped" else "Run")
            }
        }
        item {
            Panel {
                Text("Running ${all.count { it.status == "RUNNING" }}  ·  Waiting ${all.count { it.status == "QUEUED" || it.status == "AWAITING_APPROVAL" }}\n" +
                    "Completed ${all.count { it.status == "DONE" || it.status == "UNVERIFIED" }}  ·  Failed ${all.count { it.status == "FAILED" || it.status == "CANCELLED" }}", fontSize = 14.sp)
            }
        }
        if (runs.isEmpty()) item { Text("No commands yet.", color = TextLo) }
        items(runs, key = { it.id }) { run ->
            val finished = run.tasks.count { it.status in setOf("DONE", "UNVERIFIED", "FAILED", "CANCELLED") }
            Panel {
                Text(run.command, fontWeight = FontWeight.Medium)
                Text(run.note, color = TextLo, fontSize = 12.sp)
                if (run.tasks.isNotEmpty()) LinearProgressIndicator(
                    progress = { finished.toFloat() / run.tasks.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                run.tasks.forEach { t ->
                    Column(Modifier.padding(top = 8.dp)) {
                        Row {
                            Text(t.tool, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(statusLabel(t), color = statusColor(t.status, accent), fontSize = 12.sp)
                        }
                        (t.output ?: t.error)?.let {
                            Text(it, fontSize = 13.sp, color = if (t.error != null) Bad else TextHi)
                        }
                        Row {
                            if (t.status == "AWAITING_APPROVAL") {
                                TextButton(onClick = { vm.approveTask(t.id) }) { Text("Approve") }
                                TextButton(onClick = { vm.rejectTask(t.id) }) { Text("Reject", color = Bad) }
                            } else if (t.status == "QUEUED" || t.status == "RUNNING") {
                                TextButton(onClick = { vm.cancelTask(t.id) }) { Text("Cancel", color = Bad) }
                            }
                        }
                    }
                }
            }
        }
    }
}
