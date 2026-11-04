package com.lethargicrunner.gempicker

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

/**
 * Stocke le résultat de la demande de projection (consentement utilisateur,
 * valable pour une session). Android exige un nouveau consentement par session.
 */
object MediaProjectionHolder {
    @Volatile var resultCode: Int = 0
    @Volatile var data: Intent? = null
    @Volatile var projectionManager: MediaProjectionManager? = null

    fun hasSession(): Boolean = data != null

    fun requestProjectionAgain() {
        // À appeler depuis l'activité pour relancer le prompt système :
        // activity.startActivityForResult(manager.createScreenCaptureIntent(), REQ)
        projectionManager?.let { /* see MainActivity */ }
    }

    fun register(context: Context) {
        projectionManager = context.getSystemService(MediaProjectionManager::class.java)
    }
}
