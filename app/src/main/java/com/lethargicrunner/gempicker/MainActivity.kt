package com.lethargicrunner.gempicker

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object { const val REQ_PROJECTION = 42 }

    private var bubble: FloatingBubble? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaProjectionHolder.register(applicationContext)

        // --- Permissions (1er lancement) ---
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("$packageName/.AutoTapAccessibilityService") == true
        if (!enabled) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val button = Button(this).apply { text = getString(R.string.show_bubble) }
        setContentView(button)
        bubble = FloatingBubble(applicationContext)
        button.setOnClickListener {
            bubble?.show()
            requestProjectionThenPlay()
        }
    }

    private fun requestProjectionThenPlay() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            MediaProjectionHolder.resultCode = resultCode
            MediaProjectionHolder.data = data
            // La bulle est affichée : l'utilisateur lance le jeu et tape la bulle pour activer
            moveTaskToBack(true)
        }
    }
}
