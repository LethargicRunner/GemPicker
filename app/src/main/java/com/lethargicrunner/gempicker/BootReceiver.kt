package com.lethargicrunner.gempicker

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Au démarrage du téléphone : le consentement de capture (MediaProjection)
 * est TOUJOURS perdu (règle Android, une session par boot maximum).
 * On affiche une notification : un tap dessus rouvre l'app, qui redemande
 * le consentement et remet la bulle en place.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val channelId = "gempicker_boot"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Réveil de GemPicker", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val openApp = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, 11, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = Notification.Builder(context, channelId)
            .setContentTitle("GemPicker")
            .setContentText("Touchez pour réactiver la capture de la gemme")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(BOOT_NOTIF_ID, notif)
    }

    companion object { const val BOOT_NOTIF_ID = 100 }
}
