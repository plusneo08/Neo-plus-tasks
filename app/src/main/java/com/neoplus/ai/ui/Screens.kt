package com.neoplus.ai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

internal val Ink = Color(0xFF070B14)
internal val PanelBg = Color(0xFF0E1626)
internal val Line = Color(0xFF1C2A44)
internal val TextHi = Color(0xFFE6EEF9)
internal val TextLo = Color(0xFF8394AD)
internal val Bad = Color(0xFFFF5C6C)
internal val Good = Color(0xFF4ADE80)
private val Accents = listOf(0xFF35E0FF, 0xFFFFB347, 0xFFA78BFA, 0xFF4ADE80)

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Dashboard),
    Agent("Agent", Icons.Filled.AutoAwesome),
    Inbox("Inbox", Icons.Filled.Email),
    Calls("Calls", Icons.Filled.Call),
    More("More", Icons.Filled.MoreHoriz)
}

@Composable
fun NeoRoot(vm: DashboardViewModel = viewModel()) {
    val accent by vm.accent.collectAsStateWithLifecycle()
    val a = Color(accent)
    val scheme = darkColorScheme(
        primary = a, onPrimary = Ink, background = Ink, surface = PanelBg,
        onSurface = TextHi, onBackground = TextHi, surfaceVariant = PanelBg, outline = Line
    )
    MaterialTheme(colorScheme = scheme) {
        var tab by remember { mutableStateOf(Tab.Home) }
        val msg by vm.message.collectAsStateWithLifecycle()
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.clearMessage() } }
        Scaffold(
            containerColor = Ink,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar(containerColor = PanelBg) {
                    Tab.values().forEach {
                        NavigationBarItem(
                            selected = tab == it, onClick = { tab = it },
                            icon = { Icon(it.icon, it.label) },
                            label = { Text(it.label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Ink, indicatorColor = a,
                                selectedTextColor = a, unselectedIconColor = TextLo,
                                unselectedTextColor = TextLo
                            )
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.Home -> HomeScreen(vm)
                    Tab.Agent -> AgentScreen(vm)
                    Tab.Inbox -> InboxScreen(vm)
                    Tab.Calls -> CallsScreen(vm)
                    Tab.More -> MoreScreen(vm)
                }
            }
        }
    }
}

@Composable
internal fun Title(t: String, sub: String? = null) = Column(Modifier.padding(bottom = 12.dp)) {
    Text(t, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = TextHi)
    if (sub != null) Text(sub, fontSize = 13.sp, color = TextLo)
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelBg)
            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(14.dp),
        content = content
    )

@Composable
private fun MoreScreen(vm: DashboardViewModel) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Apps", "Memory", "Activity", "Security").forEachIndexed { i, t ->
                FilterChip(selected = sub == i, onClick = { sub = i }, label = { Text(t) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (sub) { 0 -> AppsScreen(vm); 1 -> MemoryScreen(vm); 2 -> LogScreen(vm); else -> SecurityScreen(vm) }
        }
    }
}

// ---------------------------------------------------------------- Home
@Composable
private fun HomeScreen(vm: DashboardViewModel) {
    val stopped by vm.stopped.collectAsStateWithLifecycle()
    val conns by vm.connections.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val mems by vm.memories.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.85f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p"
    )
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val ring = if (stopped) Bad else accent
                Canvas(Modifier.size(150.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(ring.copy(alpha = 0.12f), radius = r * (if (stopped) 1f else pulse))
                    drawCircle(ring, radius = r * 0.78f, style = Stroke(4.dp.toPx()))
                    drawCircle(ring.copy(alpha = 0.5f), radius = r * 0.5f, style = Stroke(1.5.dp.toPx()))
                    drawCircle(ring, radius = 6.dp.toPx(), center = Offset(center.x, center.y))
                }
                Spacer(Modifier.height(8.dp))
                Text("NEO PLUS AI", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(if (stopped) "Stopped. No actions will run." else "Ready.",
                    color = if (stopped) Bad else TextLo, fontSize = 13.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Connected apps", conns.count { it.connected }.toString(), Modifier.weight(1f))
                Stat("Memories", mems.size.toString(), Modifier.weight(1f))
                Stat("Log entries", logs.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            Button(
                onClick = { vm.setEmergencyStop(!stopped) },
                colors = ButtonDefaults.buttonColors(containerColor = if (stopped) accent else Bad,
                    contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (stopped) "Resume agent" else "Emergency stop", fontWeight = FontWeight.SemiBold) }
        }
        item { Text("Latest activity", color = TextLo, fontSize = 13.sp) }
        if (logs.isEmpty()) item { Text("Nothing yet. Connect an app or save a memory.", color = TextLo) }
        items(logs.take(5), key = { it.id }) { LogRow(it) }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) = Panel(modifier) {
    Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    Text(label, fontSize = 11.sp, color = TextLo)
}

// ---------------------------------------------------------------- Apps
@Composable
private fun AppsScreen(vm: DashboardViewModel) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val conns by vm.connections.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshApps() }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Apps", "Detected on this phone. Connecting only records your consent; actions come in later phases.") }
        items(apps, key = { it.spec.packageName }) { e ->
            val c = conns.firstOrNull { it.packageName == e.spec.packageName }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.spec.label, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text(if (e.installed) "Installed" else "Not installed",
                            color = if (e.installed) Good else TextLo, fontSize = 12.sp)
                    }
                    Switch(checked = c?.connected == true, enabled = e.installed || c?.connected == true,
                        onCheckedChange = { vm.setConnected(e, it) })
                }
                Text("Needs: ${e.spec.how}", color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                if (c?.connected == true) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Allow monitoring", Modifier.weight(1f), fontSize = 13.sp)
                        Switch(checked = c.monitoring, onCheckedChange = { vm.setMonitoring(e, it) })
                    }
                }
                if (e.installed) TextButton(onClick = { vm.openApp(e) }) { Text("Open") }
            }
        }
    }
}

// ---------------------------------------------------------------- Memory
@Composable
private fun MemoryScreen(vm: DashboardViewModel) {
    val mems by vm.memories.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    val shown = mems.filter { it.text.contains(query, ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Memory", "Only what you add here is remembered. Stored encrypted.") }
        item {
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text("Add a memory") })
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addMemory(draft); draft = "" }, enabled = draft.isNotBlank()) { Text("Save") }
                OutlinedButton(onClick = { exportMemories(ctx, mems) }, enabled = mems.isNotEmpty()) { Text("Export") }
            }
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search memories") },
                leadingIcon = { Icon(Icons.Filled.Search, null) })
        }
        if (shown.isEmpty()) item { Text(if (mems.isEmpty()) "No memories yet." else "No matches.", color = TextLo) }
        items(shown, key = { it.id }) { m ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.text)
                        Text(DateFormat.getDateTimeInstance().format(Date(m.createdAt)), color = TextLo, fontSize = 11.sp)
                    }
                    IconButton(onClick = { vm.deleteMemory(m.id) }) { Icon(Icons.Filled.Delete, "Delete memory", tint = Bad) }
                }
            }
        }
    }
}

private fun exportMemories(ctx: Context, mems: List<Memory>) {
    val text = mems.joinToString("\n") { "- ${it.text}" }
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, "Export memories").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------------------------------------------------------- Activity
@Composable
private fun LogScreen(vm: DashboardViewModel) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Title("Activity", "Every action is logged with its verified result.") }
        if (logs.isEmpty()) item { Text("No activity yet.", color = TextLo) }
        items(logs, key = { it.id }) { LogRow(it) }
    }
}

@Composable
private fun LogRow(l: com.neoplus.ai.data.ActivityLog) = Panel {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (l.status == "OK") Good else Bad))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(l.message, fontSize = 14.sp)
            Text("${l.source} · ${l.status} · ${DateFormat.getTimeInstance().format(Date(l.timestamp))}",
                color = TextLo, fontSize = 11.sp)
        }
    }
}

// ---------------------------------------------------------------- Security
private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun SecurityScreen(vm: DashboardViewModel) {
    val ctx = LocalContext.current
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val stopped by vm.stopped.collectAsStateWithLifecycle()
    val accent by vm.accent.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val perms = buildList {
        if (Build.VERSION.SDK_INT >= 33) add("Notifications" to Manifest.permission.POST_NOTIFICATIONS)
        add("Microphone" to Manifest.permission.RECORD_AUDIO)
        add("Contacts" to Manifest.permission.READ_CONTACTS)
        add("Calendar" to Manifest.permission.READ_CALENDAR)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Security", "Permissions, keys, and your data.") }
        item {
            Panel {
                Text("Permissions", fontWeight = FontWeight.Medium)
                key(tick) {
                    perms.forEach { (name, p) ->
                        val ok = granted(ctx, p)
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f))
                            Text(if (ok) "Granted" else "Not granted", color = if (ok) Good else Bad, fontSize = 13.sp)
                        }
                    }
                }
                Row {
                    Button(onClick = { launcher.launch(perms.map { it.second }.toTypedArray()) },
                        modifier = Modifier.padding(top = 10.dp)) { Text("Request permissions") }
                }
                Text("To revoke, open Android app settings.", color = TextLo, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp).clickable {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
            }
        }
        item {
            Panel {
                Text("AI API key", fontWeight = FontWeight.Medium)
                Text(if (hasKey) "Stored encrypted in the Android Keystore." else "No key stored.",
                    color = TextLo, fontSize = 13.sp)
                OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Paste key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { vm.saveApiKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text("Save key") }
                    OutlinedButton(onClick = { vm.clearApiKey() }, enabled = hasKey) { Text("Remove key") }
                }
            }
        }
        item {
            val model by vm.model.collectAsStateWithLifecycle()
            var m by remember(model) { mutableStateOf(model) }
            Panel {
                Text("AI model", fontWeight = FontWeight.Medium)
                Text("Used for voice calls. Change it if the service retires this name.", color = TextLo, fontSize = 12.sp)
                OutlinedTextField(m, { m = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true)
                Button(onClick = { vm.setModel(m) }, enabled = m.isNotBlank() && m != model,
                    modifier = Modifier.padding(top = 8.dp)) { Text("Save model") }
            }
        }
        item {
            Panel {
                Text("Accent colour", fontWeight = FontWeight.Medium)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Accents.forEach { c ->
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                            .border(if (accent == c.toInt()) 3.dp else 0.dp, TextHi, CircleShape)
                            .clickable { vm.setAccent(c.toInt()) })
                    }
                }
            }
        }
        item {
            Panel {
                Text("Agent", fontWeight = FontWeight.Medium)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Emergency stop", Modifier.weight(1f))
                    Switch(checked = stopped, onCheckedChange = { vm.setEmergencyStop(it) })
                }
            }
        }
        item {
            Panel {
                Text("Your data", fontWeight = FontWeight.Medium)
                OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Delete all data", color = Bad)
                }
            }
        }
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Delete all data?") },
        text = { Text("Memories, app connections, activity log and the API key will be removed. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { vm.deleteAllData(); confirmWipe = false }) { Text("Delete", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } }
    )
}
