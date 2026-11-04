package com.mio.launchertv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.tv.TvContract
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Acciones de los botones especiales del control (Netflix, YouTube, Entradas, Ajustes...).
// Las usan tanto el launcher como el servicio de botones.
object RemoteActions {

    const val EXTRA = "remote_action"

    // true = la app incluye el servicio de accesibilidad "Botones del control".
    // Google Play Protect puede bloquear la instalacion por usarlo; en ese caso se pone false
    // y se quita el <service> del AndroidManifest.xml.
    const val SERVICE_AVAILABLE = false

    val NETFLIX = listOf("com.netflix.ninja")
    val YOUTUBE = listOf("com.google.android.youtube.tv")
    val PRIME = listOf("com.amazon.amazonvideo.livingroom")
    val PLAY_STORE = listOf("com.android.vending")

    // Administradores de archivos / USB (se usa el primero que este instalado).
    val FILE_APPS = listOf(
        "com.google.android.apps.nbu.files", "com.mediatek.filemanager",
        "com.android.documentsui", "com.google.android.documentsui"
    )

    // App de TV en vivo (para el boton de TV y de entradas del control).
    val TV_APPS = listOf(
        "com.google.android.tv", "com.android.tv", "org.droidtv.channels",
        "com.sony.dtv.tvx", "com.tcl.tv", "com.hisense.tv", "com.xiaomi.mitv.tvplayer"
    )

    fun appAction(packages: List<String>) = "app:" + packages.joinToString("|")

    // Identifica el boton por su codigo, o por su scanCode si Android no lo reconoce (codigo 0).
    fun keyId(event: KeyEvent): String =
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN) "k${event.keyCode}" else "s${event.scanCode}"

    // Teclas normales (navegacion, volumen, numeros, multimedia...): no se pueden asignar.
    fun isCommonKey(code: Int): Boolean =
        code in 7..16 || code in 144..153 ||
            code in setOf(
                3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 56, 62, 66, 67, 69, 82, 84, 85, 86, 87,
                88, 89, 90, 111, 126, 127, 130, 164, 166, 167, 187, 219, 223, 224, 231
            )

    // ---------- Asignaciones guardadas ----------

    private fun readMap(prefs: SharedPreferences): MutableMap<String, String> {
        val map = linkedMapOf<String, String>()
        for (entry in (prefs.getString("remote_map", "") ?: "").split(";")) {
            val i = entry.indexOf('=')
            if (i > 0) map[entry.substring(0, i)] = entry.substring(i + 1)
        }
        return map
    }

    // Que hace este boton: primero lo que asignaste, y si no, los codigos estandar.
    fun actionFor(prefs: SharedPreferences, event: KeyEvent): String? {
        val saved = readMap(prefs)[keyId(event)]
        if (saved != null) return saved
        return when (event.keyCode) {
            KeyEvent.KEYCODE_SETTINGS -> "settings"
            KeyEvent.KEYCODE_TV_INPUT -> "inputs"
            KeyEvent.KEYCODE_TV -> "tv"
            284 -> "apps"
            else -> null
        }
    }

    private fun assign(prefs: SharedPreferences, keyId: String, action: String) {
        val map = readMap(prefs)
        map.values.removeAll { it == action }
        map[keyId] = action
        prefs.edit()
            .putString("remote_map", map.entries.joinToString(";") { "${it.key}=${it.value}" })
            .apply()
    }

    // Esa accion ya tiene un boton asignado?
    fun isAssigned(prefs: SharedPreferences, action: String): Boolean =
        readMap(prefs).containsValue(action)

    fun clearAll(prefs: SharedPreferences) {
        prefs.edit().remove("remote_map").apply()
    }

    // ---------- Modo "asignar boton" ----------

    fun startLearn(prefs: SharedPreferences, action: String, label: String) {
        prefs.edit()
            .putString("learn_label", label)
            .putLong("learn_until", System.currentTimeMillis() + 30000)
            .putString("learn_action", action)
            .apply()
    }

    fun stopLearn(prefs: SharedPreferences) {
        prefs.edit().remove("learn_action").remove("learn_label").remove("learn_until").apply()
    }

    // Si se esta esperando un boton, guarda este y devuelve true.
    fun tryLearn(ctx: Context, prefs: SharedPreferences, event: KeyEvent): Boolean {
        val action = prefs.getString("learn_action", null) ?: return false
        if (System.currentTimeMillis() > prefs.getLong("learn_until", 0L)) {
            stopLearn(prefs)
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (isCommonKey(event.keyCode)) return false
        val label = prefs.getString("learn_label", "") ?: ""
        assign(prefs, keyId(event), action)
        stopLearn(prefs)
        Toast.makeText(
            ctx,
            "Boton asignado a $label (${KeyEvent.keyCodeToString(event.keyCode)})",
            Toast.LENGTH_LONG
        ).show()
        return true
    }

    // ---------- Registro del servicio (para saber por que se desactiva) ----------

    fun log(ctx: Context, message: String) {
        try {
            val prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            val old = (prefs.getString("svc_log", "") ?: "").split("\n").filter { it.isNotEmpty() }
            val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
            val lines = (old + "$time  $message").takeLast(14)
            prefs.edit().putString("svc_log", lines.joinToString("\n")).commit()
        } catch (e: Exception) {
            // el registro nunca debe romper nada
        }
    }

    fun readLog(prefs: SharedPreferences): String = prefs.getString("svc_log", "") ?: ""

    // ---------- Ejecutar acciones ----------

    fun run(ctx: Context, action: String) {
        when {
            action == "settings" -> start(ctx, Intent(Settings.ACTION_SETTINGS))
            action == "tv" -> openTv(ctx)
            action == "usb" -> openUsb(ctx)
            action == "inputs" || action == "apps" ->
                start(ctx, Intent(ctx, MainActivity::class.java).putExtra(EXTRA, action))
            action.startsWith("app:") -> {
                var intent: Intent? = null
                for (pkg in action.removePrefix("app:").split("|")) {
                    intent = launchIntent(ctx, pkg)
                    if (intent != null) break
                }
                if (intent != null) {
                    start(ctx, intent)
                } else {
                    Toast.makeText(ctx, "Esa app no esta instalada", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun openTv(ctx: Context) {
        for (pkg in TV_APPS) {
            val intent = launchIntent(ctx, pkg)
            if (intent != null) {
                start(ctx, intent)
                return
            }
        }
        // Si no es ninguna conocida, busca una app que se llame "TV", "Canales", etc.
        val pm = ctx.packageManager
        val names = setOf(
            "tv", "live tv", "tv en vivo", "tv en directo", "canales", "live channels",
            "television", "televisión"
        )
        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        for (query in queries) {
            for (info in pm.queryIntentActivities(query, 0)) {
                val label = info.loadLabel(pm).toString().trim().lowercase()
                if (label in names) {
                    val intent = launchIntent(ctx, info.activityInfo.packageName)
                    if (intent != null) {
                        start(ctx, intent)
                        return
                    }
                }
            }
        }
        start(ctx, Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI))
    }

    // Boton USB: abre el administrador de archivos; si no hay, el selector de archivos del sistema.
    fun openUsb(ctx: Context) {
        for (pkg in FILE_APPS) {
            val intent = launchIntent(ctx, pkg)
            if (intent != null) {
                start(ctx, intent)
                return
            }
        }
        start(
            ctx,
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        )
    }

    fun launchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: ctx.packageManager.getLaunchIntentForPackage(pkg)

    private fun start(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "No pude abrir esa opcion", Toast.LENGTH_LONG).show()
        }
    }
}
