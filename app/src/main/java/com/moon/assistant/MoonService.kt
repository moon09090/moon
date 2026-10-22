package com.moon.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener as VoskListener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MoonService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var loadingModel = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var overlay: FrameLayout? = null
    private var subView: TextView? = null
    private var busy = false

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        goForeground()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag(Config.LANGUAGE)
                ttsReady = true
            }
        }
        startWakeWord()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("moon", "Moon", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "moon")
            .setContentTitle("Moon is listening")
            .setContentText("Say \"Moon\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- wake word (Vosk, offline) ----------
    private val wakeListener = object : VoskListener {
        override fun onPartialResult(h: String?) { if (h?.contains("moon") == true) onWake() }
        override fun onResult(h: String?) { if (h?.contains("moon") == true) onWake() }
        override fun onFinalResult(h: String?) {}
        override fun onError(e: Exception?) { toast("Wake word error: ${e?.message}") }
        override fun onTimeout() {}
    }

    private fun startWakeWord() {
        val m = model
        if (m == null) { loadModelThen { startWakeWord() }; return }
        try {
            val rec = Recognizer(m, 16000.0f, "[\"moon\", \"[unk]\"]")
            speech = SpeechService(rec, 16000.0f).also { it.startListening(wakeListener) }
        } catch (e: Exception) {
            toast("Wake word error: ${e.message}")
        }
    }

    private fun stopWakeWord() {
        try { speech?.stop(); speech?.shutdown() } catch (_: Exception) {}
        speech = null
    }

    private fun loadModelThen(next: () -> Unit) {
        if (loadingModel) return
        loadingModel = true
        toast("Loading Moon voice model...")
        thread {
            try {
                val dir = File(filesDir, "vosk-model")
                val done = File(dir, ".done")
                if (!done.exists()) {
                    dir.deleteRecursively()
                    copyAssets("model", dir)
                    done.createNewFile()
                }
                model = Model(dir.absolutePath)
                main.post { loadingModel = false; next() }
            } catch (e: Exception) {
                loadingModel = false
                toast("Model error: ${e.message}")
            }
        }
    }

    private fun copyAssets(path: String, out: File) {
        val list = assets.list(path) ?: return
        if (list.isEmpty()) {
            out.parentFile?.mkdirs()
            assets.open(path).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        } else {
            out.mkdirs()
            for (n in list) copyAssets("$path/$n", File(out, n))
        }
    }

    private fun onWake() {
        main.post {
            if (busy) return@post
            busy = true
            stopWakeWord()
            showOverlay()
            speak("Yes boss") { startListening() }
        }
    }

    // ---------- listening ----------
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition not available"); endSession(); return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { subView?.text = "Listening..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { endSession() }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) {
                p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let { subView?.text = it }
            }
            override fun onResults(res: Bundle?) {
                val text = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) endSession() else handleCommand(text)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Config.LANGUAGE)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(i)
    }

    // ---------- brain (Python server in Termux) ----------
    private fun handleCommand(text: String) {
        subView?.text = "\"$text\""
        thread {
            val reply = try {
                askServer(text)
            } catch (e: Exception) {
                JSONObject().put("say", "Sorry boss, the Moon server in Termux is not running.")
            }
            main.post {
                speak(reply.optString("say", "Done boss")) {
                    perform(reply.optString("action", "none"), reply.optString("arg", ""))
                    endSession()
                }
            }
        }
    }

    private fun askServer(text: String): JSONObject {
        val c = URL(Config.SERVER_URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 5000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(body)
    }

    private fun perform(action: String, arg: String) {
        try {
            val newTask = Intent.FLAG_ACTIVITY_NEW_TASK
            when (action) {
                "open_url" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(arg)).addFlags(newTask))
                "search" -> startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(arg)))
                        .addFlags(newTask)
                )
                "open_app" -> packageManager.getLaunchIntentForPackage(arg)
                    ?.let { startActivity(it.addFlags(newTask)) }
                "torch_on", "torch_off" -> {
                    val cm = getSystemService(CameraManager::class.java)
                    cm.setTorchMode(cm.cameraIdList[0], action == "torch_on")
                }
            }
        } catch (_: Exception) {}
    }

    private fun endSession() {
        main.post {
            recognizer?.destroy()
            recognizer = null
            hideOverlay()
            busy = false
            main.postDelayed({ startWakeWord() }, 600)
        }
    }

    // ---------- speech ----------
    private fun speak(text: String, done: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady) { main.post(done); return }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { main.post(done) }
            override fun onError(u: String?) { main.post(done) }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, System.nanoTime().toString())
    }

    // ---------- rainbow overlay ----------
    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun showOverlay() {
        if (overlay != null) return
        val root = FrameLayout(this)
        root.addView(RainbowView(this), FrameLayout.LayoutParams(-1, -1))

        val title = TextView(this).apply {
            text = "Yes, Boss..."
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD_ITALIC)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setShadowLayer(14f, 0f, 3f, 0xAA000000.toInt())
        }
        val sub = TextView(this).apply {
            textSize = 16f
            setTextColor(0xEEFFFFFF.toInt())
            gravity = Gravity.CENTER
            setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt())
        }
        subView = sub
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(title)
            addView(sub)
        }
        root.addView(
            col,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
                .apply { bottomMargin = dp(70) }
        )
        root.alpha = 0f

        val lp = WindowManager.LayoutParams(
            -1, dp(260),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        try {
            wm.addView(root, lp)
            root.animate().alpha(1f).setDuration(350).start()
            overlay = root
        } catch (e: Exception) {
            toast("Allow 'Display over other apps' for Moon")
        }
    }

    private fun hideOverlay() {
        val o = overlay ?: return
        overlay = null
        o.animate().alpha(0f).setDuration(350).withEndAction {
            try { wm.removeView(o) } catch (_: Exception) {}
        }.start()
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        stopWakeWord()
        model?.close()
        recognizer?.destroy()
        tts?.shutdown()
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
