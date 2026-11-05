package com.lethargicrunner.gempicker

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal d'erreur embarqué : tout événement important (démarrage, capture,
 * détection, crash, refus de permission) est écrit dans un fichier local,
 * consultable et partageable depuis MainActivity.
 * En cas de crash, la pile d'appel complète est enregistrée AVANT que
 * l'app ne meure.
 */
object GemLog {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX_LINES = 2000

    private fun file(context: Context): File =
        File(File(context.filesDir, "logs").apply { mkdirs() }, "gempicker.log")

    @Synchronized
    fun write(level: String, tag: String, message: String) {
        try {
            val f = file(appContext ?: return)
            f.appendText("${fmt.format(Date())} $level/$tag: $message\n")
            // Taille maîtrisée : garder les MAX_LINES dernières lignes
            if (f.length() > MAX_LINES * 120) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(MAX_LINES / 2).joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) {}
    }

    fun i(tag: String, message: String) = write("I", tag, message)
    fun w(tag: String, message: String) = write("W", tag, message)
    fun e(tag: String, message: String) = write("E", tag, message)

    /** Pile complète d'une exception, multi-lignes. */
    fun e(tag: String, message: String, tr: Throwable) {
        write("E", tag, "$message : ${tr.javaClass.name}: ${tr.message}")
        tr.stackTrace.take(15).forEach { write("E", tag, "  at $it") }
    }

    /** Lit le journal complet. */
    @Synchronized
    fun read(): String = try {
        appContext?.let { file(it).readText() } ?: "(contexte indisponible)"
    } catch (e: Exception) { "(journal illisible : ${e.message})" }

    /** Efface le journal. */
    @Synchronized
    fun clear() {
        try { appContext?.let { file(it).delete() } } catch (_: Exception) {}
    }

    @Volatile var appContext: Context? = null

    /** À appeler au démarrage : capture le contexte + les crashes. */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            write("F", "CRASH", "Exception non interceptée sur le thread ${thread.name}")
            e("CRASH", "Cause", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        i("GemLog", "Journal installé (version $APP_VERSION)")
    }

    const val APP_VERSION = "0.1.0"
}
