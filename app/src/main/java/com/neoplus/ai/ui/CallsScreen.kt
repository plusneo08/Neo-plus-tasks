package com.neoplus.ai.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private fun pickTime(ctx: Context, onPicked: (Long) -> Unit) {
    val now = Calendar.getInstance()
    TimePickerDialog(ctx, { _, h, m ->
        val t = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        onPicked(t.timeInMillis)
    }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
}

@Composable
internal fun CallsScreen(vm: DashboardViewModel) {
    val ctx = LocalContext.current
    val items by vm.callbacks.collectAsStateWithLifecycle()
    val exact by vm.exactAlarms.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshAlarmStatus() }
    val open = items.filter { it.status == "PENDING" || it.status == "RINGING" }
    val past = items - open.toSet()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Calls", "NEO rings you inside the app over the internet. No phone number or SIM is used.") }
        item {
            Panel {
                Text("Call me", fontWeight = FontWeight.Medium)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.addCallback(System.currentTimeMillis() + 2000, false) }) { Text("Now") }
                    OutlinedButton(onClick = { vm.addCallback(System.currentTimeMillis() + 10 * 60_000L, false) }) { Text("In 10 min") }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime(ctx) { vm.addCallback(it, false) } }) { Text("At a time") }
                    OutlinedButton(onClick = { pickTime(ctx) { vm.addCallback(it, true) } }) { Text("Every day at") }
                }
                Text("Rings up to 3 times, 2 minutes apart. Needs the Notifications permission.",
                    color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (!exact && Build.VERSION.SDK_INT >= 31) item {
            Panel {
                Text("Exact alarms are off", fontWeight = FontWeight.Medium, color = Bad)
                Text("Without them, calls can arrive a few minutes late.", color = TextLo, fontSize = 13.sp)
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow exact alarms") }
            }
        }
        item { Text("Upcoming", color = TextLo, fontSize = 13.sp) }
        if (open.isEmpty()) item { Text("No calls booked.", color = TextLo) }
        items(open, key = { it.id }) { c ->
            Panel {
                Text(DateFormat.getDateTimeInstance().format(Date(c.triggerAt)), fontWeight = FontWeight.Medium)
                Text("${c.status.lowercase()}${if (c.repeatDaily) " · daily" else ""} · attempt ${c.attempts} of ${c.maxAttempts}",
                    color = TextLo, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.cancelCallback(c.id) }) { Text("Cancel", color = Bad) }
                    TextButton(onClick = { pickTime(ctx) { vm.rescheduleCallback(c.id, it) } }) { Text("Reschedule") }
                }
            }
        }
        item { Text("History", color = TextLo, fontSize = 13.sp) }
        if (past.isEmpty()) item { Text("No finished calls yet.", color = TextLo) }
        items(past, key = { it.id }) { c ->
            Panel {
                Text(DateFormat.getDateTimeInstance().format(Date(c.triggerAt)))
                Text("${c.status.lowercase()} · ${c.attempts} attempt(s)", color = if (c.status == "ANSWERED") Good else TextLo, fontSize = 12.sp)
                if (c.status != "ANSWERED") TextButton(onClick = { pickTime(ctx) { vm.rescheduleCallback(c.id, it) } }) { Text("Call me again at…") }
            }
        }
    }
}
