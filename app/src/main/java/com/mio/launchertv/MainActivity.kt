package com.mio.launchertv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.graphics.Rect
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    // ===== EDITA AQUI QUE APPS SALEN =====
    // Cada app es una lista de nombres de paquete posibles; se usa el primero instalado.
    private val netflix = listOf("com.netflix.ninja")
    private val prime = listOf("com.amazon.amazonvideo.livingroom")
    private val hbo = listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.hbomax", "com.hbo.max")
    private val disney = listOf("com.disney.disneyplus")
    private val youtube = listOf("com.google.android.youtube.tv")
    private val youtubeMusic = listOf("com.google.android.youtube.tvmusic")
    private val spotify = listOf("com.spotify.tv.android")
    private val playStore = listOf("com.android.vending")

    // App de TV en vivo (se usa solo si la TV no se puede mostrar dentro del launcher).
    private val tvApps = listOf(
        "com.google.android.tv", "com.android.tv", "org.droidtv.channels",
        "com.sony.dtv.tvx", "com.tcl.tv", "com.hisense.tv", "com.xiaomi.mitv.tvplayer"
    )

    // Orden inicial de las apps (despues puedes moverlas tu con "Mover").
    private val favoritePackages =
        netflix + prime + youtube + youtubeMusic + hbo + disney + spotify + playStore
    // =====================================

    private enum class Mode { HOME, APPS }

    private class Chan(val inputId: String, val id: Long, val number: String, val name: String)

    private lateinit var prefs: SharedPreferences
    private lateinit var tvView: TvView
    private lateinit var menuBar: View
    private lateinit var appsRow: RecyclerView
    private lateinit var appName: TextView
    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelList: RecyclerView
    private lateinit var channelOverlay: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val hideMenuRunnable = Runnable { hideMenu() }
    private val hideBannerRunnable = Runnable {
        if (channelBuffer.isEmpty()) channelOverlay.visibility = View.GONE
    }
    private val commitChannel = Runnable { tuneChannel() }

    private var mode = Mode.HOME
    private var homeAdapter: AppsAdapter? = null
    private var movingPkg: String? = null
    private var dialogOpen = false
    private var resumed = false

    // TV dentro del launcher
    private var channels: List<Chan> = emptyList()
    private var channelsLoaded = false
    private var loadingChannels = false
    private var channelIndex = -1
    private var tvActive = false
    private var embedFailed = false
    private var warnedNoChannels = false
    private var channelBuffer = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)

        tvView = findViewById(R.id.tvView)
        menuBar = findViewById(R.id.menuBar)
        appsRow = findViewById(R.id.appsRow)
        appsRow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        appName = findViewById(R.id.appName)
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

        tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(inputId: String?) {
                tvFailed()
            }
        })

        // Cuadro que muestra el numero de canal (al escribirlo) y el canal actual
        channelOverlay = TextView(this).apply {
            textSize = 40f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.scrim_pill)
            setPadding(dp(24), dp(10), dp(24), dp(10))
            visibility = View.GONE
        }
        addContentView(
            channelOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(40), dp(50), 0) }
        )

        // Menu inferior
        click(R.id.search) { openAssistant() }
        click(R.id.iconNotifications) { openNotifications() }
        click(R.id.iconSettings) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        click(R.id.profile) { }
        click(R.id.tabApps) { showMode(Mode.APPS) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (mode == Mode.HOME) {
            refreshHome()
            showMenu(force = true)
        } else {
            showApps()
        }
        startTv()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(hideMenuRunnable)
        // Suelta el sintonizador para que otras apps (Netflix, la app de TV...) lo puedan usar
        tvView.reset()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // En un launcher, "atras" nunca cierra la pantalla.
        if (mode != Mode.HOME) {
            showMode(Mode.HOME)
        } else if (tvActive && menuBar.visibility == View.VISIBLE) {
            hideMenu()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        // Mientras se mueve una app, las flechas y OK controlan el movimiento.
        if (movingPkg != null && mode == Mode.HOME) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) moveCurrent(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> {
                    if (first) endMove()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // Numeros del control: escribir canal (ej. 3 . 1)
        val ch = keyChar(event.keyCode)
        if (ch != null) {
            if (first) typeChannel(ch)
            return true
        }

        // Con un canal escrito: OK lo sintoniza ya, ATRAS lo cancela
        if (channelBuffer.isNotEmpty()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (first) {
                        handler.removeCallbacks(commitChannel)
                        tuneChannel()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (down) cancelChannel()
                    return true
                }
            }
        }

        // Canal +/- del control
        if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
            event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
        ) {
            if (first) stepChannel(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
            return true
        }

        if (mode == Mode.HOME) {
            if (menuBar.visibility != View.VISIBLE) {
                // Menu oculto: cualquier flecha / OK lo vuelve a mostrar
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU -> {
                        if (first) showMenu()
                        return true
                    }
                }
            } else if (down) {
                scheduleHide()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Menu inferior: mostrar / ocultar ----------

    private fun showMenu(force: Boolean = false) {
        if (mode != Mode.HOME) return
        if (force || menuBar.visibility != View.VISIBLE) {
            menuBar.visibility = View.VISIBLE
            focusFirstApp()
        }
        scheduleHide()
    }

    private fun hideMenu() {
        // Si no hay TV de fondo, el menu se queda siempre visible.
        if (!tvActive || mode != Mode.HOME || movingPkg != null || dialogOpen) return
        menuBar.visibility = View.INVISIBLE
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideMenuRunnable)
        handler.postDelayed(hideMenuRunnable, 8000)
    }

    private fun focusFirstApp() {
        appsRow.post {
            val first = appsRow.findViewHolderForAdapterPosition(0)?.itemView
                ?: appsRow.getChildAt(0)
            if (first != null) first.requestFocus() else appsRow.requestFocus()
        }
    }

    // ---------- TV en vivo dentro del launcher ----------

    private fun startTv() {
        if (embedFailed) {
            setTvOff()
            return
        }
        if (channelsLoaded) {
            resumeTv()
        } else if (!loadingChannels) {
            loadingChannels = true
            Thread {
                val list = try {
                    readChannels()
                } catch (e: Exception) {
                    emptyList()
                }
                runOnUiThread {
                    channels = list
                    channelsLoaded = true
                    loadingChannels = false
                    resumeTv()
                }
            }.start()
        }
    }

    private fun resumeTv() {
        if (!resumed) return
        if (channels.isEmpty()) {
            setTvOff()
            if (!warnedNoChannels) {
                warnedNoChannels = true
                Toast.makeText(
                    this,
                    "No puedo leer los canales de este TV (Android lo bloquea a otras apps). " +
                        "Al escribir un canal se abrira la app de TV.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        val idx = if (channelIndex in channels.indices) channelIndex else savedChannelIndex()
        tuneIndex(idx, announce = false)
    }

    private fun setTvOff() {
        tvActive = false
        tvView.reset()
        tvView.visibility = View.GONE
        if (mode == Mode.HOME) showMenu()
    }

    private fun tvFailed() {
        embedFailed = true
        Toast.makeText(
            this,
            "Android no deja mostrar la TV dentro del launcher; el canal se abrira en la app de TV.",
            Toast.LENGTH_LONG
        ).show()
        setTvOff()
    }

    private fun tuneIndex(index: Int, announce: Boolean) {
        val chan = channels.getOrNull(index) ?: return
        channelIndex = index
        try {
            tvView.visibility = View.VISIBLE
            tvView.setStreamVolume(1f)
            tvView.tune(chan.inputId, TvContract.buildChannelUri(chan.id))
            tvActive = true
        } catch (e: Exception) {
            tvFailed()
            return
        }
        prefs.edit().putString("lastChannel", "${chan.inputId}|${chan.id}").apply()
        if (announce) showBanner("${chan.number}  ${chan.name}")
    }

    private fun savedChannelIndex(): Int {
        val saved = prefs.getString("lastChannel", null) ?: return 0
        val i = channels.indexOfFirst { "${it.inputId}|${it.id}" == saved }
        return if (i >= 0) i else 0
    }

    private fun stepChannel(delta: Int) {
        if (!tvActive || channels.isEmpty()) return
        val next = (channelIndex + delta + channels.size) % channels.size
        tuneIndex(next, announce = true)
    }

    private fun showBanner(text: String) {
        channelOverlay.text = text
        channelOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideBannerRunnable)
        handler.postDelayed(hideBannerRunnable, 3000)
    }

    // Lee los canales de los sintonizadores del TV (antena / cable).
    private fun readChannels(): List<Chan> {
        val manager = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        val out = mutableListOf<Chan>()
        for (input in manager.tvInputList) {
            if (input.type != TvInputInfo.TYPE_TUNER) continue
            try {
                contentResolver.query(
                    TvContract.buildChannelsUriForInput(input.id),
                    arrayOf("_id", "display_number", "display_name"), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val number = c.str("display_number")?.trim()?.replace('-', '.') ?: continue
                        out.add(Chan(input.id, c.getLong(0), number, c.str("display_name") ?: ""))
                    }
                }
            } catch (e: Exception) {
                // este sintonizador no deja leer sus canales
            }
        }
        return out.sortedWith(
            compareBy<Chan>({ numPart(it.number, 0) }, { numPart(it.number, 1) })
        )
    }

    private fun numPart(number: String, i: Int): Int =
        number.split('.').getOrNull(i)?.toIntOrNull() ?: 0

    // ---------- Canales por numero ----------

    private fun keyChar(code: Int): Char? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (code - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
            '0' + (code - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.KEYCODE_MINUS,
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> '.'
        else -> null
    }

    private fun typeChannel(ch: Char) {
        if (ch == '.' && (channelBuffer.isEmpty() || channelBuffer.contains('.'))) return
        if (channelBuffer.length >= 7) return
        channelBuffer += ch
        channelOverlay.text = channelBuffer
        channelOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideBannerRunnable)
        // Si no escribes nada mas en 2 segundos, se sintoniza solo
        handler.removeCallbacks(commitChannel)
        handler.postDelayed(commitChannel, 2000)
    }

    private fun cancelChannel() {
        handler.removeCallbacks(commitChannel)
        channelBuffer = ""
        channelOverlay.visibility = View.GONE
    }

    private fun tuneChannel() {
        val typed = channelBuffer.trimEnd('.')
        cancelChannel()
        if (typed.isEmpty()) return
        if (!channelsLoaded) {
            Toast.makeText(this, "Cargando canales, intenta de nuevo", Toast.LENGTH_SHORT).show()
            return
        }
        if (channels.isEmpty()) {
            openTvApp(typed)
            return
        }
        val idx = findChannelIndex(typed)
        when {
            idx < 0 -> Toast.makeText(this, "El canal $typed no existe", Toast.LENGTH_SHORT).show()
            embedFailed -> openChannel(channels[idx].id, typed)
            else -> {
                if (mode != Mode.HOME) showMode(Mode.HOME)
                tuneIndex(idx, announce = true)
            }
        }
    }

    // Acepta "31" como "3.1" y "3-1" como "3.1".
    private fun findChannelIndex(typed: String): Int {
        val candidates = linkedSetOf(typed)
        if (!typed.contains('.')) {
            for (i in 1 until typed.length) {
                candidates.add(typed.substring(0, i) + "." + typed.substring(i))
            }
        }
        for (candidate in candidates) {
            val i = channels.indexOfFirst { it.number == candidate }
            if (i >= 0) return i
        }
        return -1
    }

    private fun openChannel(id: Long, typed: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(this, "Canal $typed", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            openTvApp(typed)
        }
    }

    // Respaldo: abre la app de TV del propio aparato.
    private fun openTvApp(typed: String) {
        val pkg = installed(tvApps)
        if (pkg != null) {
            Toast.makeText(this, "Abriendo TV (canal $typed)", Toast.LENGTH_LONG).show()
            openPackage(pkg)
        } else {
            Toast.makeText(this, "No encontre la app de TV", Toast.LENGTH_LONG).show()
        }
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
        if (newMode == Mode.HOME) {
            panel.visibility = View.GONE
            refreshHome()
            showMenu(force = true)
        } else {
            handler.removeCallbacks(hideMenuRunnable)
            menuBar.visibility = View.GONE
            panel.visibility = View.VISIBLE
            showApps()
        }
    }

    private fun refreshHome() {
        movingPkg = null
        appName.text = ""
        val adapter = AppsAdapter(homeApps())
        homeAdapter = adapter
        appsRow.adapter = adapter
    }

    private fun showApps() {
        panelTitle.text = "Todas las apps"
        panelList.layoutManager = GridLayoutManager(this, 7)
        panelList.adapter = AppsAdapter(allApps())
    }

    private fun Cursor.str(column: String): String? {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getString(i) else null
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

    // Apps de inicio: sin las ocultas, y en el orden que elegiste (si moviste alguna).
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
        val isHidden = pkg in hiddenSet()
        val options = mutableListOf<String>()
        if (mode == Mode.HOME) options.add("Mover")
        options.add(if (isHidden) "Mostrar en inicio" else "Quitar de inicio")
        options.add("Cancelar")

        dialogOpen = true
        AlertDialog.Builder(this)
            .setTitle(info.loadLabel(packageManager).toString())
            .setOnDismissListener { dialogOpen = false }
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Mover" -> startMove(info)
                    "Cancelar" -> {}
                    else -> {
                        setHidden(pkg, !isHidden)
                        Toast.makeText(
                            this,
                            if (isHidden) "Ahora aparece en inicio" else "Quitada de inicio",
                            Toast.LENGTH_SHORT
                        ).show()
                        when (mode) {
                            Mode.HOME -> refreshHome()
                            Mode.APPS -> showApps()
                        }
                    }
                }
            }
            .show()
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
        appsRow.scrollToPosition(pos)
        focusHomeItem(pos)
    }

    private fun focusHomeItem(pos: Int) {
        appsRow.post {
            appsRow.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
    }

    private fun moveCurrent(delta: Int) {
        val pkg = movingPkg ?: return
        val adapter = homeAdapter ?: return
        val from = adapter.indexOfPackage(pkg)
        val to = from + delta
        if (from < 0 || to < 0 || to >= adapter.itemCount) return
        adapter.move(from, to)
        saveOrder(adapter.packages())
        appsRow.scrollToPosition(to)
        focusHomeItem(to)
    }

    private fun endMove() {
        movingPkg = null
        appName.text = ""
        Toast.makeText(this, "Orden guardado", Toast.LENGTH_SHORT).show()
        scheduleHide()
    }

    // ---------- Utilidades ----------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // Como en Android TV: la app enfocada queda al borde izquierdo de la fila,
    // y las anteriores salen por completo, sin quedar cortadas a la mitad.
    private fun alignLeft(v: View) {
        val rv = v.parent as? RecyclerView ?: return
        val dx = v.left - rv.paddingLeft
        if (dx != 0) rv.smoothScrollBy(dx, 0)
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

    private fun installed(candidates: List<String>): String? =
        candidates.firstOrNull { launchIntent(it) != null }

    // ---------- Adaptador de apps ----------

    private inner class AppsAdapter(initial: List<ResolveInfo>) :
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

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
                if (hasFocus && movingPkg == null) {
                    appName.text = info.loadLabel(pm)
                    if (v.parent === appsRow) alignLeft(v)
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
