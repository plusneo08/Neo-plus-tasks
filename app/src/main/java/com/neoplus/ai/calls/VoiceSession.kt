package com.neoplus.ai.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.neoplus.ai.security.Prefs
import kotlinx.coroutines.*
import java.util.Locale

enum class Lang(val label: String, val tag: String?, val name: String, val voice: Boolean) {
    AUTO("Auto", null, "the same language the owner uses", true),
    EN("English", "en-US", "English", true),
    SW("Swahili", "sw", "Swahili", true),
    FR("French", "fr-FR", "French", true),
    PT("Portuguese", "pt-PT", "Portuguese", true),
    // Android has no Chichewa speech recognition or voice, so this mode is typed text only.
    NY("Chichewa (typed)", null, "Chichewa", false)
}

/** One live conversation: speech in, AI reply, speech out. Main-thread only. */
class VoiceSession(private val ctx: Context, private val prefs: Prefs, private var reason: String) {
    val lines = mutableStateListOf<Pair<String, String>>()
    var status by mutableStateOf("Connecting")
    var error by mutableStateOf<String?>(null)
    var lang by mutableStateOf(Lang.AUTO)
    var muted by mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history = mutableListOf<Pair<String, String>>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var rec: SpeechRecognizer? = null
    private var active = false
    private var speaking = false
    private var errorStreak = 0

    private fun hasMic() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(callReason: String = reason) {
        reason = callReason
        active = true
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) {
            rec = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(recListener) }
        } else error = "Speech recognition isn't available on this phone. You can type instead."
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setOnUtteranceProgressListener(ttsListener)
                greet()
            } else {
                error = "Text-to-speech isn't available. Replies will show as text."
                greet()
            }
        }
    }

    private fun greet() {
        val g = buildString {
            append("Hi, it's NEO.")
            if (reason.isNotBlank()) append(" You asked me to call about: $reason.")
            append(" How can I help?")
        }
        lines.add("NEO" to g)
        history.add("model" to g)
        say(g)
    }

    private fun system() = "You are NEO, the owner's personal AI assistant, on a live voice call with the owner. " +
        "Reply in ${lang.name}. Keep replies to one or two short spoken sentences, no markdown. " +
        "You cannot yet read messages, make phone calls, or change anything on the phone from this call; " +
        "if asked, say so plainly and never claim an action was done."

    private fun say(text: String) {
        val t = tts
        val voice = lang.voice && ttsReady && t != null
        if (!voice) { status = "Reply shown as text"; main.postDelayed({ listen() }, 200); return }
        val loc = lang.tag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        val r = t!!.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            error = "No ${lang.label} voice installed. Reply shown as text."
            main.postDelayed({ listen() }, 200); return
        }
        speaking = true
        status = "Speaking"
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
    }

    private val ttsListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { main.post { speaking = false; listen() } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { main.post { speaking = false; listen() } }
    }

    fun listen() {
        if (!active || muted || speaking || !lang.voice) { if (active && !lang.voice) status = "Type your message"; return }
        val r = rec ?: return
        if (!hasMic()) { error = "Microphone permission is off. Allow it in Security, or type instead."; status = "Mic off"; return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            lang.tag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        status = "Listening"
        runCatching { r.startListening(i) }.onFailure { error = "Could not start listening: ${it.message}" }
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { errorStreak = 0 }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) listen() else userSaid(text)
        }
        override fun onError(code: Int) {
            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    main.postDelayed({ listen() }, 300)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ listen() }, 800)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    error = "Microphone permission is off. Type instead."; status = "Mic off"
                }
                12, 13 -> { error = "${lang.label} isn't supported for speech on this phone. Type instead."; status = "Type your message" }
                else -> if (++errorStreak < 4) main.postDelayed({ listen() }, 1200)
                else { error = "Speech recognition keeps failing (code $code). Type instead."; status = "Type your message" }
            }
        }
    }

    /** Spoken or typed input. */
    fun userSaid(text: String) {
        if (text.isBlank() || !active) return
        tts?.stop(); speaking = false
        lines.add("You" to text.trim())
        history.add("user" to text.trim())
        status = "Thinking"
        val key = prefs.apiKey(); val model = prefs.model; val sys = system(); val snapshot = history.toList()
        scope.launch {
            val r = withContext(Dispatchers.IO) { Ai.reply(key, model, sys, snapshot) }
            r.onSuccess {
                error = null
                lines.add("NEO" to it); history.add("model" to it); say(it)
            }.onFailure {
                error = it.message ?: "AI request failed"
                status = "AI unavailable"
                history.removeLastOrNull()
                if (lang.voice) main.postDelayed({ listen() }, 500)
            }
        }
    }

    fun interrupt() { tts?.stop(); speaking = false; listen() }

    fun toggleMute() {
        muted = !muted
        if (muted) { rec?.cancel(); status = "Muted" } else listen()
    }

    fun setLanguage(l: Lang) { rec?.cancel(); tts?.stop(); speaking = false; lang = l; listen() }

    fun stop() {
        active = false
        scope.cancel()
        runCatching { rec?.destroy() }
        runCatching { tts?.stop(); tts?.shutdown() }
    }
}
