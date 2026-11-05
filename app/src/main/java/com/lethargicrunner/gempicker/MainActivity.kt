package com.lethargicrunner.gempicker

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object { const val REQ_PROJECTION = 42 }

    private var bubble: FloatingBubble? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaProjectionHolder.register(applicationContext)

        // --- Permissions (1er lancement) ---
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "1/3 : autorisez GemPicker à s'afficher par-dessus les apps", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("$packageName/.AutoTapAccessibilityService") == true
        if (!enabled) {
            Toast.makeText(this, "2/3 : activez le service d'accessibilité GemPicker", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // 3/3 : notifications (pour la notification de réveil au boot)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }

        // Tout est prêt : bulle + demande de capture, sans bouton.
        bubble = FloatingBubble(applicationContext)
        bubble?.show()
        requestProjectionThenPlay()
    }

    private fun requestProjectionThenPlay() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                MediaProjectionHolder.resultCode = resultCode
                MediaProjectionHolder.data = data
                // Session validée : on masque l'icône de l'écran d'accueil.
                setIconVisible(false)
                Toast.makeText(this, "Prêt ! Lancez le jeu et tapez la bulle", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                // Refus : on garde l'icône visible pour pouvoir réessayer.
                setIconVisible(true)
                Toast.makeText(this,
                    "Sans la capture, GemPicker ne peut pas voir la gemme. Réessayez via l'icône.",
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /** Masque/ré-affiche l'icône du lanceur (l'alias seulement, pas l'activité). */
    private fun setIconVisible(visible: Boolean) {
        val alias = ComponentName(this, "$packageName.IconAlias")
        val state = if (visible)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONTKILL_APP)
    }
}
