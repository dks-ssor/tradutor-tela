package com.tradutor.tela
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val reqOverlay = 1
    private val reqCapture = 2
    private val reqNotif = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val info = TextView(this).apply {
            text = "Toque em Iniciar, abra o jogo e a tradução aparecerá sobre a tela."
            textSize = 16f
        }
        val btnStart = Button(this).apply {
            text = "Iniciar"
            setOnClickListener { start() }
        }
        val btnStop = Button(this).apply {
            text = "Parar"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TranslateService::class.java))
            }
        }
        layout.addView(info)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(i, reqOverlay)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), reqNotif
            )
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqNotif) start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqOverlay) {
            start()
        } else if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, TranslateService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            ContextCompat.startForegroundService(this, i)
            moveTaskToBack(true)
        }
    }
}
