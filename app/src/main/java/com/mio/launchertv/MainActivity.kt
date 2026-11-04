package com.mio.launchertv

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    // ===== EDITA AQUI EL ORDEN INICIAL DE LAS APPS =====
    // Cada app es una lista de nombres de paquete posibles; se usa el primero instalado.
    private val netflix = listOf("com.netflix.ninja")
    private val prime = listOf("com.amazon.amazonvideo.livingroom")
    private val hbo = listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.hbomax", "com.hbo.max")
    private val disney = listOf("com.disney.disneyplus")
    private val youtube = listOf("com.google.android.youtube.tv")
    private val youtubeMusic = listOf("com.google.android.youtube.tvmusic")
    private val spotify = listOf("com.spotify.tv.android")
    private val playStore = listOf("com.android.vending")


    // Cuantas apps se ven a la vez en "Mis apps" (el tamaño de cada una se ajusta solo).
    private val homeColumns = 8

    // Estas salen primero; el resto va despues, en orden alfabetico.
    // Despues puedes moverlas tu con "Mover".
    private val favoritePackages =
        netflix + prime + youtube + youtubeMusic + hbo + disney + spotify + playStore
    // ===================================================

    private enum class Mode { HOME, APPS }

    private lateinit var prefs: SharedPreferences
    private lateinit var appsRow: RecyclerView
    private lateinit var appName: TextView
    private lateinit var heroGroup: View
    private lateinit var homeContent: View
    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelList: RecyclerView

    private var mode = Mode.HOME
    private var selectedTab: View? = null
    private var homeAdapter: AppsAdapter? = null
    private var movingPkg: String? = null
    private var rowStart = 0        // primera app visible de la fila de inicio
    private var tileWidth = 0       // ancho de cada app en la fila de inicio (px)
    private var tileHeight = 0
    private var consumedKey: String? = null
    private var pendingAction: String? = null
    private var isForeground = false
    private var pendingFocusHome = false
    private val openDialogs = mutableListOf<Dialog>()
    private var learnDialog: AlertDialog? = null
    private var learnListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        pendingAction = intent?.getStringExtra(RemoteActions.EXTRA)

        appsRow = findViewById(R.id.appsRow)
        appsRow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        // La fila muestra exactamente "homeColumns" apps completas, repartidas a lo ancho.
        val availPx = resources.displayMetrics.widthPixels - dp(80) - appsRow.paddingLeft
        val slotPx = availPx / homeColumns
        tileWidth = slotPx - dp(10)
        tileHeight = tileWidth * 60 / 108
        val rowParams = appsRow.layoutParams
        rowParams.width = appsRow.paddingLeft + slotPx * homeColumns
        appsRow.layoutParams = rowParams
        appName = findViewById(R.id.appName)
        heroGroup = findViewById(R.id.heroGroup)
        homeContent = findViewById(R.id.homeContent)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        panelList = findViewById(R.id.panelList)
        panelList.clipChildren = false
        panelList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
            ) {
                outRect.bottom = dp(10)
            }
        })

        // Barra superior
        click(R.id.search) { openAssistant() }
        click(R.id.iconInputs) { showInputs() }
        click(R.id.iconNotifications) { showQuickPanel() }
        click(R.id.iconSettings) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        click(R.id.profile) { showRemoteSetup() }

        // Tarjeta "Mejora tus recomendaciones": solo se puede seleccionar, no abre nada
        val card = findViewById<View>(R.id.recoCard)
        card.setOnClickListener { }

        // El texto de Buscar y de las pestañas se pone NEGRO al seleccionarlos
        // (el fondo se vuelve blanco) y vuelve a su color al soltarlos.
        for (id in listOf(R.id.search, R.id.tabHome, R.id.tabShop, R.id.tabApps)) {
            findViewById<View>(id).setOnFocusChangeListener { _, _ -> refreshTextColors() }
        }

        // Pestañas
        selectTab(findViewById(R.id.tabHome))
        click(R.id.tabHome) { showMode(Mode.HOME) }
        click(R.id.tabShop) { openFirst(playStore) }
        click(R.id.tabApps) { showMode(Mode.APPS) }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        when (mode) {
            Mode.HOME -> refreshHome()
            Mode.APPS -> showApps()
        }
        if (pendingFocusHome && mode == Mode.HOME) {
            pendingFocusHome = false
            focusHomeItem(0)
        }
        processPending()
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    // El servicio de botones (o el sistema) nos pide hacer algo: abrir entradas, todas las apps...
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Tecla HOME del control: siempre vuelve al inicio, estes donde estes.
        if (intent.hasCategory(Intent.CATEGORY_HOME) &&
            intent.getStringExtra(RemoteActions.EXTRA) == null
        ) {
            goHome()
            return
        }
        pendingAction = intent.getStringExtra(RemoteActions.EXTRA)
        if (isForeground) processPending()
    }

    // Cierra menus, sale de "Apps" y deja el foco en la primera app de inicio.
    private fun goHome() {
        dismissAllDialogs()
        movingPkg = null
        showMode(Mode.HOME)
        if (isForeground) {
            pendingFocusHome = false
            focusHomeItem(0)
        } else {
            pendingFocusHome = true
        }
    }

    private fun dismissAllDialogs() {
        for (dialog in openDialogs.toList()) {
            if (dialog.isShowing) dialog.dismiss()
        }
        openDialogs.clear()
    }

    // Igual que AlertDialog.Builder, pero recuerda los menus abiertos para poder cerrarlos con HOME.
    private inner class TrackedBuilder : AlertDialog.Builder(this@MainActivity) {
        override fun show(): AlertDialog {
            val dialog = super.show()
            openDialogs.removeAll { !it.isShowing }
            openDialogs.add(dialog)
            return dialog
        }
    }

    private fun processPending() {
        val action = pendingAction ?: return
        pendingAction = null
        runRemoteAction(action)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // En un launcher, "atras" solo vuelve al inicio; nunca cierra la pantalla.
        if (mode != Mode.HOME) showMode(Mode.HOME)
    }

    // Mientras se mueve una app, las flechas izquierda/derecha la mueven y OK la deja ahi.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (movingPkg != null && mode == Mode.HOME) {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) moveCurrent(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> {
                    if (down && event.repeatCount == 0) endMove()
                    return true
                }
            }
        }

        // Botones especiales del control (ajustes, entradas, TV, Netflix, YouTube...)
        val id = RemoteActions.keyId(event)
        if (event.action == KeyEvent.ACTION_UP && id == consumedKey) {
            consumedKey = null
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (RemoteActions.tryLearn(this, prefs, event)) {
                consumedKey = id
                return true
            }
            val action = RemoteActions.actionFor(prefs, event)
            if (action != null) {
                consumedKey = id
                runRemoteAction(action)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Botones especiales del control ----------

    private fun runRemoteAction(action: String) {
        when (action) {
            "inputs" -> showInputs()
            "apps" -> showMode(Mode.APPS)
            else -> RemoteActions.run(this, action)
        }
    }

    private fun openTvApp() {
        RemoteActions.openTv(this)
    }

    // Pantalla para asignar cada boton del control (se abre con el circulo "G" de arriba).
    private fun showRemoteSetup() {
        val entries = listOf(
            "Netflix" to RemoteActions.appAction(RemoteActions.NETFLIX),
            "YouTube" to RemoteActions.appAction(RemoteActions.YOUTUBE),
            "Prime Video" to RemoteActions.appAction(RemoteActions.PRIME),
            "Google Play" to RemoteActions.appAction(RemoteActions.PLAY_STORE),
            "Apps (boton rosado)" to "apps",
            "Entradas" to "inputs",
            "TV en vivo" to "tv",
            "Ajustes" to "settings",
            "USB (archivos)" to "usb"
        )
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (RemoteActions.SERVICE_AVAILABLE) {
            val title = if (serviceEnabled()) "Servicio de botones: ACTIVADO"
            else "1) ACTIVAR el servicio de botones"
            items.add(title to { openAccessibilitySettings() })
            items.add("Ver estado del servicio" to { showServiceLog() })
            items.add("Info de la app (ajustes restringidos)" to { openAppInfo() })
        }
        for ((label, action) in entries) {
            val mark = if (RemoteActions.isAssigned(prefs, action)) "   ✓ asignado" else ""
            items.add("Boton $label$mark" to { requestLearn(label, action) })
        }
        items.add("Asignar un boton a otra app..." to { chooseAppToAssign() })
        items.add(
            "Borrar todas las asignaciones" to {
                RemoteActions.clearAll(prefs)
                Toast.makeText(this, "Asignaciones borradas", Toast.LENGTH_SHORT).show()
            }
        )

        TrackedBuilder()
            .setTitle("Botones del control")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    // Muestra que paso con el servicio (conectado, desconectado, errores) para saber por que se apaga.
    private fun showServiceLog() {
        val text = RemoteActions.readLog(prefs)
        TrackedBuilder()
            .setTitle("Estado del servicio")
            .setMessage(
                if (text.isBlank()) {
                    "Sin registros: el sistema nunca llego a iniciar el servicio " +
                        "(lo bloquea antes de encenderlo)."
                } else {
                    text
                }
            )
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Borrar") { _, _ -> prefs.edit().remove("svc_log").apply() }
            .show()
    }

    private fun openAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No pude abrir la info de la app", Toast.LENGTH_LONG).show()
        }
    }

    // Avisa si falta activar el servicio antes de esperar el boton.
    private fun requestLearn(label: String, action: String) {
        if (RemoteActions.SERVICE_AVAILABLE && !serviceEnabled()) {
            TrackedBuilder()
                .setTitle("Falta activar el servicio")
                .setMessage(
                    "Para que el launcher vea los botones del control, primero activa " +
                        "\"Botones del control\" en Accesibilidad."
                )
                .setPositiveButton("Activar ahora") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("Asignar igual") { _, _ -> learnButton(label, action) }
                .show()
        } else {
            learnButton(label, action)
        }
    }

    // Elige cualquier app instalada y luego el boton que la abrira.
    private fun chooseAppToAssign() {
        val apps = allApps()
        val names = apps.map { it.loadLabel(packageManager).toString() }
        TrackedBuilder()
            .setTitle("¿Que app abrira el boton?")
            .setItems(names.toTypedArray()) { _, which ->
                val pkg = apps[which].activityInfo.packageName
                requestLearn(names[which], RemoteActions.appAction(listOf(pkg)))
            }
            .show()
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, RemoteKeyService::class.java)
        return enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Abre: Ajustes > Preferencias del dispositivo > Accesibilidad > Botones del control",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Espera a que pulses un boton del control y se lo asigna a esa accion.
    private fun learnButton(label: String, action: String) {
        RemoteActions.startLearn(prefs, action, label)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "learn_action" && p.getString("learn_action", null) == null) {
                learnDialog?.dismiss()
            }
        }
        learnListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)

        val dialog = TrackedBuilder()
            .setTitle(label)
            .setMessage(
                "Pulsa ahora el boton del control que quieres usar para esto.\n\n" +
                    (if (RemoteActions.SERVICE_AVAILABLE) "Si no pasa nada, primero activa el servicio de botones.\n"
                    else "Si no pasa nada, este TV no deja que el launcher vea ese boton.\n") +
                    "(Atras para cancelar)"
            )
            .setOnKeyListener { _, _, event -> RemoteActions.tryLearn(this, prefs, event) }
            .setOnDismissListener {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
                learnListener = null
                learnDialog = null
                RemoteActions.stopLearn(prefs)
            }
            .create()
        learnDialog = dialog
        openDialogs.add(dialog)
        dialog.show()
    }

    // Boton de entradas: lista HDMI / AV / TV y abre la que elijas.
    private fun showInputs() {
        val ids = mutableListOf<String?>()
        val labels = mutableListOf<String>()
        ids.add(null)
        labels.add("TV en vivo")
        try {
            val manager = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
            for (input in manager.tvInputList) {
                if (!input.isPassthroughInput()) continue
                val label = (input.loadCustomLabel(this) ?: input.loadLabel(this))?.toString()
                if (label.isNullOrBlank()) continue
                ids.add(input.id)
                labels.add(label)
            }
        } catch (e: Exception) {
            // si no se pueden leer las entradas, queda solo "TV en vivo"
        }

        TrackedBuilder()
            .setTitle("Entradas")
            .setItems(labels.toTypedArray()) { _, which ->
                val id = ids[which]
                if (id == null) {
                    openTvApp()
                } else {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Toast.makeText(this, "No pude abrir esa entrada", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    // ---------- Asistente de Google ----------

    private fun openAssistant() {
        val attempts = listOf(Intent(Intent.ACTION_ASSIST), Intent(Intent.ACTION_VOICE_COMMAND))
        for (intent in attempts) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // prueba la siguiente opcion
            }
        }
        openPackage("com.google.android.katniss")
    }

    // ---------- Panel rapido (como el de Google TV) ----------

    private class QuickTile(
        val icon: Int,
        val title: String,
        val subtitle: String?,
        val action: () -> Unit
    )

    // Abre la primera pantalla de ajustes que exista; si ninguna, los ajustes generales.
    private fun openSettingsScreen(vararg actions: String) {
        for (action in actions) {
            try {
                startActivity(Intent(action))
                return
            } catch (e: Exception) {
                // prueba la siguiente
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "No pude abrir los ajustes", Toast.LENGTH_LONG).show()
        }
    }

    private fun startScreensaver() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
            )
        } catch (e: Exception) {
            openSettingsScreen(Settings.ACTION_DREAM_SETTINGS)
        }
    }

    // Texto e icono oscuros cuando el mosaico esta seleccionado (fondo celeste), claros si no.
    private fun bindQuickFocus(root: View, icon: ImageView?, title: TextView?, subtitle: TextView?) {
        val light = 0xFFE8EAF0.toInt()
        val dim = 0xFFAAB4C3.toInt()
        val dark = 0xFF0B1B2B.toInt()
        val darkDim = 0xFF3C4F63.toInt()
        fun paint(focused: Boolean) {
            title?.setTextColor(if (focused) dark else light)
            subtitle?.setTextColor(if (focused) darkDim else dim)
            icon?.setColorFilter(if (focused) dark else light)
        }
        paint(false)
        root.setOnFocusChangeListener { _, hasFocus -> paint(hasFocus) }
    }

    // Panel lateral con Wi-Fi, Bluetooth, accesibilidad... Se abre con el icono de arriba.
    private fun showQuickPanel() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LayoutInflater.from(this).inflate(R.layout.panel_quick, null)
        dialog.setContentView(root)

        val tiles = listOf(
            QuickTile(R.drawable.ic_qs_dream, "Protector de pantalla", null) { startScreensaver() },
            QuickTile(R.drawable.ic_qs_wifi, "Wi-Fi", null) {
                openSettingsScreen(Settings.ACTION_WIFI_SETTINGS)
            },
            QuickTile(R.drawable.ic_qs_home, "Google Home", null) {
                if (installed(listOf("com.google.android.apps.chromecast.app")) != null) {
                    openFirst(listOf("com.google.android.apps.chromecast.app"))
                } else {
                    Toast.makeText(this, "Google Home no esta instalado", Toast.LENGTH_SHORT).show()
                }
            },
            QuickTile(R.drawable.ic_qs_bluetooth, "Bluetooth", null) {
                openSettingsScreen(Settings.ACTION_BLUETOOTH_SETTINGS)
            },
            QuickTile(R.drawable.ic_qs_timer, "Temporizador", null) {
                openSettingsScreen(Settings.ACTION_SETTINGS)
            },
            QuickTile(R.drawable.ic_qs_audio, "Salida de audio", null) {
                openSettingsScreen("com.android.tv.action.VIEW_SOUND_SETTINGS", Settings.ACTION_SOUND_SETTINGS)
            },
            QuickTile(R.drawable.ic_qs_access, "Accesibilidad", null) {
                openSettingsScreen(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        )

        val grid = root.findViewById<LinearLayout>(R.id.qsGrid)
        var firstTile: View? = null
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val rowParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rowParams.bottomMargin = dp(10)
            grid.addView(row, rowParams)

            for (j in 0..1) {
                val params = LinearLayout.LayoutParams(0, dp(66), 1f)
                if (j == 0) params.rightMargin = dp(5) else params.leftMargin = dp(5)
                val tile = tiles.getOrNull(i + j)
                if (tile == null) {
                    row.addView(View(this), params)
                    continue
                }
                val view = LayoutInflater.from(this).inflate(R.layout.item_quick_tile, row, false)
                val icon = view.findViewById<ImageView>(R.id.qsIcon)
                val title = view.findViewById<TextView>(R.id.qsTitle)
                val subtitle = view.findViewById<TextView>(R.id.qsSubtitle)
                icon.setImageResource(tile.icon)
                title.text = tile.title
                if (tile.subtitle != null) {
                    subtitle.text = tile.subtitle
                    subtitle.visibility = View.VISIBLE
                }
                bindQuickFocus(view, icon, title, subtitle)
                view.setOnClickListener {
                    dialog.dismiss()
                    tile.action()
                }
                row.addView(view, params)
                if (firstTile == null) firstTile = view
            }
            i += 2
        }

        // Ajustes completos (engranaje de arriba)
        val gear = root.findViewById<ImageView>(R.id.qsGear)
        bindQuickFocus(gear, gear, null, null)
        gear.setOnClickListener {
            dialog.dismiss()
            openSettingsScreen(Settings.ACTION_SETTINGS)
        }

        // Aviso de opciones para desarrolladores, solo si estan habilitadas
        val dev = root.findViewById<View>(R.id.qsDev)
        val devEnabled =
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        dev.visibility = if (devEnabled) View.VISIBLE else View.GONE
        bindQuickFocus(
            dev,
            root.findViewById<ImageView>(R.id.qsDevIcon),
            root.findViewById<TextView>(R.id.qsDevTitle),
            root.findViewById<TextView>(R.id.qsDevSub)
        )
        dev.setOnClickListener {
            dialog.dismiss()
            openSettingsScreen(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        }

        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(0))
            window.setGravity(Gravity.END)
            window.setLayout(dp(400), ViewGroup.LayoutParams.MATCH_PARENT)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.45f)
        }
        openDialogs.add(dialog)
        dialog.setOnShowListener { firstTile?.requestFocus() }
        dialog.show()
    }

    // ---------- Pantallas ----------

    private fun showMode(newMode: Mode) {
        mode = newMode
        movingPkg = null
        val home = newMode == Mode.HOME
        heroGroup.visibility = if (home) View.VISIBLE else View.GONE
        homeContent.visibility = if (home) View.VISIBLE else View.GONE
        panel.visibility = if (home) View.GONE else View.VISIBLE
        when (newMode) {
            Mode.HOME -> {
                selectTab(findViewById(R.id.tabHome))
                refreshHome()
            }
            Mode.APPS -> {
                selectTab(findViewById(R.id.tabApps))
                showApps()
            }
        }
    }

    private fun selectTab(tab: View) {
        selectedTab?.isSelected = false
        tab.isSelected = true
        selectedTab = tab
        refreshTextColors()
    }

    private fun refreshTextColors() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val soft = 0xB3FFFFFF.toInt()
        for (id in listOf(R.id.tabHome, R.id.tabShop, R.id.tabApps)) {
            val tab = findViewById<TextView>(id)
            tab.setTextColor(
                when {
                    tab.isFocused -> black
                    tab.isSelected -> white
                    else -> soft
                }
            )
        }
        findViewById<TextView>(R.id.searchLabel)
            .setTextColor(if (findViewById<View>(R.id.search).isFocused) black else soft)
    }

    private fun refreshHome() {
        movingPkg = null
        appName.text = ""
        val adapter = AppsAdapter(homeApps(), tileWidth, tileHeight)
        homeAdapter = adapter
        rowStart = 0
        appsRow.adapter = adapter
    }

    private fun showApps() {
        panelTitle.text = "Todas las apps"
        panelList.layoutManager = GridLayoutManager(this, 7)
        panelList.adapter = AppsAdapter(allApps())
    }

    // ---------- Apps ----------

    private fun hiddenSet(): MutableSet<String> =
        (prefs.getStringSet("hidden", emptySet()) ?: emptySet()).toMutableSet()

    private fun setHidden(pkg: String, hidden: Boolean) {
        val set = hiddenSet()
        if (hidden) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet("hidden", set).apply()
    }

    private fun savedOrder(): List<String> =
        (prefs.getString("order", "") ?: "").split(",").filter { it.isNotEmpty() }

    private fun saveOrder(packages: List<String>) {
        prefs.edit().putString("order", packages.joinToString(",")).apply()
    }

    // TODAS las apps instaladas (la pestaña "Apps" siempre las muestra todas).
    private fun allApps(): List<ResolveInfo> {
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val normal = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return (packageManager.queryIntentActivities(leanback, 0) +
            packageManager.queryIntentActivities(normal, 0))
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedWith(
                compareBy<ResolveInfo> {
                    val i = favoritePackages.indexOf(it.activityInfo.packageName)
                    if (i >= 0) i else Int.MAX_VALUE
                }.thenBy { it.loadLabel(packageManager).toString().lowercase() }
            )
    }

    // Apps de inicio: todas, menos las que ocultaste, en el orden que elegiste.
    private fun homeApps(): List<ResolveInfo> {
        val hidden = hiddenSet()
        val base = allApps().filter { it.activityInfo.packageName !in hidden }
        val order = savedOrder()
        if (order.isEmpty()) return base
        val position = order.withIndex().associate { it.value to it.index }
        return base.sortedBy { position[it.activityInfo.packageName] ?: Int.MAX_VALUE }
    }

    // Menu que sale al mantener pulsado OK sobre una app
    private fun showAppMenu(info: ResolveInfo) {
        val pkg = info.activityInfo.packageName
        val options = if (mode == Mode.HOME) {
            listOf("Ocultar", "Mover", "Desinstalar")
        } else if (pkg in hiddenSet()) {
            listOf("Mostrar en inicio", "Desinstalar")
        } else {
            listOf("Ocultar de inicio", "Desinstalar")
        }

        TrackedBuilder()
            .setTitle(info.loadLabel(packageManager).toString())
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Ocultar" -> hideFromHome(info)
                    "Mover" -> startMove(info)
                    "Desinstalar" -> uninstallApp(info)
                    else -> {
                        val hide = pkg !in hiddenSet()
                        setHidden(pkg, hide)
                        Toast.makeText(
                            this,
                            if (hide) "Oculta en inicio (sigue en Apps)" else "Ahora aparece en inicio",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    // Borra la app del sistema. Android muestra su propia confirmacion antes de borrarla.
    private fun uninstallApp(info: ResolveInfo) {
        val appInfo = info.activityInfo.applicationInfo
        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
        if (isSystem) {
            Toast.makeText(
                this, "Es una app del sistema, no se puede desinstalar", Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${info.activityInfo.packageName}"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No pude abrir el desinstalador", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideFromHome(info: ResolveInfo) {
        val adapter = homeAdapter ?: return
        val pkg = info.activityInfo.packageName
        val pos = adapter.indexOfPackage(pkg)
        setHidden(pkg, true)
        Toast.makeText(this, "Oculta en inicio (sigue en Apps)", Toast.LENGTH_SHORT).show()
        refreshHome()
        val count = homeAdapter?.itemCount ?: 0
        if (count > 0) focusHomeItem(minOf(maxOf(pos, 0), count - 1))
    }

    // ---------- Mover apps en inicio ----------

    private fun startMove(info: ResolveInfo) {
        val adapter = homeAdapter ?: return
        val pkg = info.activityInfo.packageName
        val pos = adapter.indexOfPackage(pkg)
        if (pos < 0) return
        movingPkg = pkg
        appName.text =
            "Moviendo ${info.loadLabel(packageManager)}:  ◀ ▶ para mover  ·  OK para dejarla aquí"
        focusHomeItem(pos)
    }

    private fun moveCurrent(delta: Int) {
        val pkg = movingPkg ?: return
        val adapter = homeAdapter ?: return
        val from = adapter.indexOfPackage(pkg)
        val to = from + delta
        if (from < 0 || to < 0 || to >= adapter.itemCount) return
        adapter.move(from, to)
        saveOrder(adapter.packages())
        focusHomeItem(to)
    }

    private fun endMove() {
        movingPkg = null
        appName.text = ""
        Toast.makeText(this, "Orden guardado", Toast.LENGTH_SHORT).show()
    }

    private fun focusHomeItem(pos: Int) {
        appsRow.scrollToPosition(pos)
        appsRow.post {
            val v = appsRow.findViewHolderForAdapterPosition(pos)?.itemView
            if (v != null) {
                v.requestFocus()
                alignWindow(pos)
            }
        }
    }

    // ---------- Utilidades ----------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // La fila se mueve "por paginas" de apps completas: la app enfocada siempre se ve entera,
    // y al llegar al borde la fila avanza una app para dejarla dentro.
    private fun alignWindow(pos: Int) {
        val count = homeAdapter?.itemCount ?: return
        if (pos < 0) return
        val n = homeColumns
        var start = rowStart
        if (pos < start) start = pos else if (pos > start + n - 1) start = pos - n + 1
        start = start.coerceIn(0, maxOf(0, count - n))
        rowStart = start
        // Primero se cancela el desplazamiento automatico de Android, que chocaba con este.
        appsRow.stopScroll()
        val scroller = object : LinearSmoothScroller(this@MainActivity) {
            override fun getHorizontalSnapPreference(): Int = LinearSmoothScroller.SNAP_TO_START
        }
        scroller.targetPosition = start
        appsRow.layoutManager?.startSmoothScroll(scroller)
    }

    private fun click(id: Int, action: () -> Unit) {
        findViewById<View>(id).setOnClickListener { action() }
    }

    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun openPackage(pkg: String) {
        launchIntent(pkg)?.let { startActivity(it) }
    }

    private fun openFirst(candidates: List<String>) {
        installed(candidates)?.let { openPackage(it) }
    }

    private fun installed(candidates: List<String>): String? =
        candidates.firstOrNull { launchIntent(it) != null }

    // ---------- Adaptador de apps ----------

    private inner class AppsAdapter(
        initial: List<ResolveInfo>,
        private val tileW: Int = 0,
        private val tileH: Int = 0
    ) :
        RecyclerView.Adapter<AppsAdapter.VH>() {

        private val items = initial.toMutableList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.appImage)
        }

        fun indexOfPackage(pkg: String) = items.indexOfFirst { it.activityInfo.packageName == pkg }

        fun packages() = items.map { it.activityInfo.packageName }

        fun move(from: Int, to: Int) {
            val item = items.removeAt(from)
            items.add(to, item)
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            if (tileW > 0) {
                val lp = view.layoutParams
                lp.width = tileW
                lp.height = tileH
                view.layoutParams = lp
            }
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val info = items[position]
            val pm = packageManager
            val banner = info.activityInfo.loadBanner(pm)
                ?: info.activityInfo.applicationInfo.loadBanner(pm)

            if (banner != null) {
                holder.image.setPadding(0, 0, 0, 0)
                holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.image.setImageDrawable(banner)
            } else {
                val p = dp(10)
                holder.image.setPadding(p, p, p, p)
                holder.image.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.image.setImageDrawable(info.loadIcon(pm))
            }

            holder.itemView.setOnClickListener {
                openPackage(info.activityInfo.packageName)
            }
            holder.itemView.setOnLongClickListener {
                showAppMenu(info)
                true
            }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                val s = if (hasFocus) 1.15f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
                if (hasFocus && v.parent === appsRow) {
                    if (movingPkg == null) appName.text = info.loadLabel(pm)
                    alignWindow(appsRow.getChildAdapterPosition(v))
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
