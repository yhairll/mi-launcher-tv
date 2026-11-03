package com.mio.launchertv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.graphics.Rect
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
        click(R.id.iconNotifications) { openNotifications() }
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
        pendingAction = intent.getStringExtra(RemoteActions.EXTRA)
        if (isForeground) processPending()
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

        AlertDialog.Builder(this)
            .setTitle("Botones del control")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    // Muestra que paso con el servicio (conectado, desconectado, errores) para saber por que se apaga.
    private fun showServiceLog() {
        val text = RemoteActions.readLog(prefs)
        AlertDialog.Builder(this)
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
            AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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

        val dialog = AlertDialog.Builder(this)
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

        AlertDialog.Builder(this)
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

    // ---------- Notificaciones ----------

    // Abre el panel de notificaciones del sistema (si el TV lo tiene).
    private fun openNotifications() {
        try {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(service)
        } catch (e: Exception) {
            Toast.makeText(this, "Este TV no tiene panel de notificaciones", Toast.LENGTH_SHORT)
                .show()
        }
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

        AlertDialog.Builder(this)
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
