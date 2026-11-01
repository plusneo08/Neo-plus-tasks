package com.neoplus.ai.calls

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neoplus.ai.data.Callback
import com.neoplus.ai.data.NeoDatabase
import com.neoplus.ai.security.Prefs
import com.neoplus.ai.ui.*
import kotlinx.coroutines.launch

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val id = intent.getLongExtra(CallbackScheduler.EXTRA_ID, -1)
        val autoAnswer = intent.getBooleanExtra(CallbackScheduler.EXTRA_ANSWER, false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(Prefs(this).accent), background = Ink,
                surface = PanelBg, onSurface = TextHi, onBackground = TextHi)) {
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    CallScreen(id, autoAnswer) { finish() }
                }
            }
        }
    }
}

@Composable
private fun CallScreen(id: Long, autoAnswer: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cb by remember { mutableStateOf<Callback?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var inCall by remember { mutableStateOf(false) }
    val session = remember { VoiceSession(ctx, Prefs(ctx), "") }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    suspend fun answer() {
        CallbackScheduler.dismissRing(ctx, id)
        CallbackScheduler.finish(ctx, id, "ANSWERED")
        inCall = true
    }

    LaunchedEffect(id) {
        cb = NeoDatabase.get(ctx).callbackDao().get(id)
        loaded = true
        if (autoAnswer && cb?.status == "RINGING") answer()
    }
    LaunchedEffect(inCall) {
        if (inCall) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            session.start(cb?.reason.orEmpty())
        }
    }
    DisposableEffect(Unit) { onDispose { session.stop() } }

    when {
        !loaded -> Box(Modifier.fillMaxSize())
        inCall -> InCall(session) { session.stop(); close() }
        cb?.status == "RINGING" -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("NEO is calling", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text("Internet call, no phone number", color = TextLo, fontSize = 14.sp)
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Button(onClick = {
                    scope.launch {
                        CallbackScheduler.dismissRing(ctx, id)
                        CallbackScheduler.finish(ctx, id, "DECLINED"); close()
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Ink)) { Text("Decline") }
                Button(onClick = { scope.launch { answer() } }) { Text("Answer") }
            }
        }
        else -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This call has ended", fontSize = 22.sp)
            Text(cb?.status?.lowercase() ?: "not found", color = TextLo)
            Spacer(Modifier.height(24.dp))
            Button(onClick = close) { Text("Close") }
        }
    }
}

@Composable
private fun InCall(s: VoiceSession, end: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(s.lines.size) { if (s.lines.isNotEmpty()) list.animateScrollToItem(s.lines.size - 1) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp)) {
        Text("In call with NEO", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(s.status, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        s.error?.let { Text(it, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Lang.values().forEach {
                FilterChip(selected = s.lang == it, onClick = { s.setLanguage(it) }, label = { Text(it.label, fontSize = 11.sp) })
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.lines.toList()) { (who, text) ->
                Column {
                    Text(who, color = TextLo, fontSize = 11.sp)
                    Text(text)
                }
            }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { s.interrupt() }) { Text("Interrupt") }
            OutlinedButton(onClick = { s.toggleMute() }) { Text(if (s.muted) "Unmute" else "Mute") }
            Button(onClick = end, colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Ink)) { Text("End") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), placeholder = { Text("Type a message") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { s.userSaid(typed); typed = "" }, enabled = typed.isNotBlank()) { Text("Send") }
        }
    }
}
