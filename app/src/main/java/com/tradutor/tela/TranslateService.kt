package com.tradutor.tela

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TranslateService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: FrameLayout? = null
    private lateinit var wm: WindowManager

    private var width = 0
    private var height = 0
    private var busy = false
    private var running = false
    private var lastKey = ""

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE)
            .build()
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()

        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        val code = intent.getIntExtra("code", 0)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        projection = proj

        val m = realMetrics()
        setupCapture(m.widthPixels, m.heightPixels, m.densityDpi)
        setupOverlay()

        running = true
        handler.postDelayed(loop, 2000)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "tradutor", "Tradutor de Tela", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, "tradutor")
        else
            Notification.Builder(this)
        val n = builder
            .setContentTitle("Tradutor de Tela")
            .setContentText("Traduzindo a tela...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return m
    }

    private fun setupCapture(w: Int, h: Int, dpi: Int) {
        reader?.close()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        val d = display
        if (d == null) {
            display = projection!!.createVirtualDisplay(
                "tradutor", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
        } else {
            d.resize(w, h, dpi)
            d.surface = r.surface
        }
        width = w
        height = h
    }

    private fun setupOverlay() {
        val c = FrameLayout(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(c, lp)
        overlay = c
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            if (!busy) startCycle()
            handler.postDelayed(this, 3000)
        }
    }

    private fun startCycle() {
        busy = true
        // esconde a tradução para o OCR ler só o texto original do jogo
        overlay?.visibility = View.INVISIBLE
        handler.postDelayed({ grab() }, 400)
    }

    private fun endCycle() {
        overlay?.visibility = View.VISIBLE
        busy = false
    }

    private fun grab() {
        if (!running) return
        val m = realMetrics()
        if (m.widthPixels != width || m.heightPixels != height) {
            // tela girou: recria a captura e tenta no próximo ciclo
            setupCapture(m.widthPixels, m.heightPixels, m.densityDpi)
            endCycle()
            return
        }
        val image = reader?.acquireLatestImage()
        if (image == null) {
            endCycle()
            return
        }
        val bitmap: Bitmap
        try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val full = Bitmap.createBitmap(
                width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            bitmap = Bitmap.createBitmap(full, 0, 0, width, height)
        } finally {
            image.close()
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { handleText(it) }
            .addOnFailureListener { endCycle() }
    }

    private fun handleText(text: Text) {
        val blocks = text.textBlocks.filter {
            it.text.trim().length > 1 && it.boundingBox != null
        }
        val key = blocks.joinToString("|") { it.text }

        if (blocks.isEmpty()) {
            overlay?.removeAllViews()
            lastKey = ""
            endCycle()
            return
        }
        if (key == lastKey) {
            endCycle()
            return
        }

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                val tasks = blocks.map { translator.translate(it.text.replace("\n", " ")) }
                Tasks.whenAllComplete(tasks).addOnCompleteListener {
                    overlay?.removeAllViews()
                    blocks.forEachIndexed { i, b ->
                        val t = tasks[i]
                        if (t.isSuccessful) {
                            addBox(b.boundingBox!!, t.result ?: "", b.lines.size)
                        }
                    }
                    lastKey = key
                    endCycle()
                }
            }
            .addOnFailureListener { endCycle() }
    }

    private fun addBox(rect: Rect, translated: String, lines: Int) {
        val tv = TextView(this)
        tv.text = translated
        tv.setTextColor(Color.WHITE)
        tv.setBackgroundColor(Color.argb(235, 0, 0, 0))
        tv.setPadding(6, 2, 6, 2)
        val size = (rect.height().toFloat() / lines.coerceAtLeast(1)) * 0.7f
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size.coerceIn(20f, 60f))
        val lp = FrameLayout.LayoutParams(
            rect.width().coerceAtLeast(100),
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.leftMargin = rect.left
        lp.topMargin = rect.top
        overlay?.addView(tv, lp)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        display?.release()
        reader?.close()
        projection?.stop()
        translator.close()
        recognizer.close()
        super.onDestroy()
    }
}
