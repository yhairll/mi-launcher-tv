package com.mio.launchertv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.graphics.Rect
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)

        appsRow = findViewById(R.id.appsRow)
        appsRow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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
        click(R.id.iconNotifications) { openNotifications() }
        click(R.id.iconSettings) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        click(R.id.profile) { }

        // Pestañas
        selectTab(findViewById(R.id.tabHome))
        click(R.id.tabHome) { showMode(Mode.HOME) }
        click(R.id.tabShop) { openFirst(playStore) }
        click(R.id.tabApps) { showMode(Mode.APPS) }
    }

    override fun onResume() {
        super.onResume()
        when (mode) {
            Mode.HOME -> refreshHome()
            Mode.APPS -> showApps()
        }
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
        return super.dispatchKeyEvent(event)
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
                centerInRow(v)
            }
        }
    }

    // ---------- Utilidades ----------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // Deja la app enfocada en el CENTRO de la fila, completa (nunca cortada a la mitad).
    // Primero se cancela el desplazamiento automatico de Android, que chocaba con este.
    private fun centerInRow(v: View) {
        val rv = v.parent as? RecyclerView ?: return
        rv.stopScroll()
        val rowCenter = rv.paddingLeft + (rv.width - rv.paddingLeft - rv.paddingRight) / 2
        val dx = (v.left + v.width / 2) - rowCenter
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

    private fun openFirst(candidates: List<String>) {
        installed(candidates)?.let { openPackage(it) }
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
                if (hasFocus && v.parent === appsRow) {
                    if (movingPkg == null) appName.text = info.loadLabel(pm)
                    centerInRow(v)
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
