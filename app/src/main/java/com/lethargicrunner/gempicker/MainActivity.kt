package com.lethargicrunner.gempicker

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object { const val REQ_PROJECTION = 42 }

    private var bubble: FloatingBubble? = null
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GemLog.install(applicationContext)
        GemLog.i("Main", "Ouverture de MainActivity")
        MediaProjectionHolder.register(applicationContext)
        buildUi()

        // --- Permissions (1er lancement) ---
        if (!Settings.canDrawOverlays(this)) {
            status("1/3 — Superposition : à autoriser")
            Toast.makeText(this, "1/3 : autorisez GemPicker à s'afficher par-dessus les apps", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("$packageName/.AutoTapAccessibilityService") == true
        if (!enabled) {
            status("2/3 — Accessibilité : à activer")
            Toast.makeText(this, "2/3 : activez le service d'accessibilité GemPicker", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }

        // Tout est prêt : bulle + demande de capture.
        status("3/3 — Capture d'écran : validez la popup")
        bubble = FloatingBubble(applicationContext)
        bubble?.show()
        requestProjectionThenPlay()
    }

    /** Petite UI : état + boutons journal (partage / effacement). */
    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        statusText = TextView(this).apply { text = "GemPicker — démarrage..." }
        root.addView(statusText)
        root.addView(Button(this).apply {
            text = "Envoyer le journal (copier vers moi)"
            setOnClickListener { shareLog() }
        })
        root.addView(Button(this).apply {
            text = "Effacer le journal"
            setOnClickListener {
                GemLog.clear()
                Toast.makeText(this@MainActivity, "Journal effacé", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(TextView(this).apply {
            text = "Tap court sur la bulle = ON/OFF — appui long = mode debug"
            setPadding(0, pad, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun status(s: String) { statusText.text = s }

    private fun shareLog() {
        val log = GemLog.read()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, log)
            putExtra(Intent.EXTRA_TITLE, "GemPicker — journal")
        }
        startActivity(Intent.createChooser(send, "Envoyer le journal"))
        // (le journal complet reste aussi consultable dans l'app)
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
                GemLog.i("Main", "Session de capture VALIDÉE")
                status("Prêt ! Lancez le jeu et tapez la bulle")
                moveTaskToBack(true)
            } else {
                GemLog.w("Main", "Popup de capture REFUSÉE (result=$resultCode)")
                status("Capture refusée — GemPicker ne peut pas voir la gemme. Rouvrez l'app pour réessayer.")
                Toast.makeText(this, "Sans la capture, impossible de voir la gemme. Réessayez.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
