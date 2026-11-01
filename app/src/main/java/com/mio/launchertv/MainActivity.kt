package com.mio.launchertv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

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

    // Estas apps salen primero (en este orden); las demas van despues, alfabeticamente.
    private val favoritePackages =
        netflix + prime + youtube + youtubeMusic + hbo + disney + spotify + playStore

    // Tarjetas de la fila de abajo
    private val cardApps = listOf(hbo, disney, youtube, netflix, prime, spotify)

    // App de TV en vivo, para cuando se quiera abrir directamente.
    private val liveTvPackages = listOf(
        "com.google.android.tv",
        "com.mediatek.wwtv.tvcenter",
        "com.android.tv"
    )
    // =====================================

    private enum class Mode { HOME, APPS, LIVE }

    private class Channel(
        val id: Long,
        val number: String,
        val name: String,
        var program: String? = null
    )

    private val epgPermission = "com.android.providers.tv.permission.READ_EPG_DATA"
    private val requestEpg = 41

    private lateinit var prefs: SharedPreferences
    private lateinit var appsRow: RecyclerView
    private lateinit var appName: TextView
    private lateinit var homeContent: View
    private lateinit var heroBox: View
    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelMessage: TextView
    private lateinit var panelAction: TextView
    private lateinit var panelList: RecyclerView

    private var mode = Mode.HOME
    private var selectedTab: View? = null
    private val io = Executors.newFixedThreadPool(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)

        appsRow = findViewById(R.id.appsRow)
        appsRow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        appName = findViewById(R.id.appName)
        homeContent = findViewById(R.id.homeContent)
        heroBox = findViewById(R.id.heroBox)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        panelMessage = findViewById(R.id.panelMessage)
        panelAction = findViewById(R.id.panelAction)
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
        click(R.id.search) { openPackage("com.google.android.katniss") }
        click(R.id.iconSettings) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        click(R.id.iconBell) { }
        click(R.id.profile) { }

        // Pestañas
        selectTab(findViewById(R.id.tabHome))
        click(R.id.tabHome) { showMode(Mode.HOME) }
        click(R.id.tabLive) { showMode(Mode.LIVE) }
        click(R.id.tabShop) { openFirst(playStore) }
        click(R.id.tabApps) { showMode(Mode.APPS) }

        // Botones de categorias: cambia lo que abre cada uno
        click(R.id.chipLive) { showMode(Mode.LIVE) }
        click(R.id.chipTop) { openFirst(playStore) }
        click(R.id.chipPopular) { openFirst(youtube) }
        click(R.id.chipTrending) { openFirst(youtube) }

        panelAction.setOnClickListener { openLiveTv() }
    }

    override fun onResume() {
        super.onResume()
        when (mode) {
            Mode.HOME -> refreshHome()
            Mode.APPS -> showApps()
            Mode.LIVE -> {}
        }
        buildCards()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // En un launcher, "atras" solo vuelve al inicio; nunca cierra la pantalla.
        if (mode != Mode.HOME) showMode(Mode.HOME)
    }

    // ---------- Pantallas ----------

    private fun showMode(newMode: Mode) {
        mode = newMode
        val home = newMode == Mode.HOME
        homeContent.visibility = if (home) View.VISIBLE else View.GONE
        heroBox.visibility = if (home) View.VISIBLE else View.GONE
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
            Mode.LIVE -> {
                selectTab(findViewById(R.id.tabLive))
                showLive()
            }
        }
    }

    private fun selectTab(tab: View) {
        selectedTab?.isSelected = false
        tab.isSelected = true
        selectedTab = tab
    }

    private fun refreshHome() {
        appName.text = ""
        appsRow.adapter = AppsAdapter(homeApps())
    }

    private fun showApps() {
        panelTitle.text = "Todas las apps"
        panelMessage.visibility = View.GONE
        panelAction.visibility = View.GONE
        panelList.layoutManager = GridLayoutManager(this, 7)
        panelList.adapter = AppsAdapter(allApps())
    }

    private fun showLive() {
        panelTitle.text = getString(R.string.tab_live)
        panelAction.visibility = View.GONE
        panelMessage.text = "Cargando canales…"
        panelMessage.visibility = View.VISIBLE
        panelList.layoutManager = GridLayoutManager(this, 3)
        panelList.adapter = ChannelsAdapter(emptyList())

        if (ContextCompat.checkSelfPermission(this, epgPermission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            loadChannels()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(epgPermission), requestEpg)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Se intenta leer igual: si Android lo bloquea, se muestra el aviso.
        if (requestCode == requestEpg && mode == Mode.LIVE) loadChannels()
    }

    // ---------- Canales en vivo ----------

    private fun loadChannels() {
        Thread {
            val result = try {
                queryChannels()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (mode != Mode.LIVE) return@runOnUiThread
                if (result.isNullOrEmpty()) {
                    showLiveUnavailable()
                } else {
                    panelMessage.visibility = View.GONE
                    panelList.adapter = ChannelsAdapter(result)
                }
            }
        }.start()
    }

    private fun queryChannels(): List<Channel> {
        val channels = mutableListOf<Channel>()
        contentResolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                TvContract.Channels.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2) ?: ""
                if (name.isBlank()) continue
                channels.add(Channel(c.getLong(0), c.getString(1) ?: "", name))
            }
        }
        if (channels.isEmpty()) return channels

        // Programa que se esta emitiendo ahora en cada canal
        try {
            val now = System.currentTimeMillis().toString()
            val titles = HashMap<Long, String>()
            contentResolver.query(
                TvContract.Programs.CONTENT_URI,
                arrayOf(TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_TITLE),
                "${TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS} <= ? AND " +
                    "${TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS} > ?",
                arrayOf(now, now),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(1)
                    if (!title.isNullOrBlank()) titles[c.getLong(0)] = title
                }
            }
            channels.forEach { it.program = titles[it.id] }
        } catch (e: Exception) {
            // sin guia: se muestran solo los canales
        }

        return channels.sortedWith(
            compareBy<Channel> {
                it.number.split("-", ".").firstOrNull()?.toIntOrNull() ?: Int.MAX_VALUE
            }.thenBy { it.number }
        )
    }

    private fun showLiveUnavailable() {
        panelList.adapter = ChannelsAdapter(emptyList())
        panelMessage.text = "Android no deja que apps de terceros lean la lista de canales " +
            "de este TV. Puedes abrir la app de TV para ver tus canales y la guía."
        panelMessage.visibility = View.VISIBLE
        panelAction.visibility = View.VISIBLE
    }

    private fun openLiveTv() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        } catch (e: Exception) {
            // sigue con la app de TV
        }
        val pkg = installed(liveTvPackages)
        if (pkg != null) {
            openPackage(pkg)
        } else {
            Toast.makeText(this, "No encontré la app de TV en vivo", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Apps ----------

    private fun hiddenSet(): MutableSet<String> =
        (prefs.getStringSet("hidden", emptySet()) ?: emptySet()).toMutableSet()

    private fun setHidden(pkg: String, hidden: Boolean) {
        val set = hiddenSet()
        if (hidden) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet("hidden", set).apply()
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

    private fun homeApps(): List<ResolveInfo> {
        val hidden = hiddenSet()
        return allApps().filter { it.activityInfo.packageName !in hidden }
    }

    // Menu que sale al mantener pulsado OK sobre una app
    private fun showAppMenu(info: ResolveInfo) {
        val pkg = info.activityInfo.packageName
        val isHidden = pkg in hiddenSet()
        val option = if (isHidden) "Mostrar en inicio" else "Quitar de inicio"
        AlertDialog.Builder(this)
            .setTitle(info.loadLabel(packageManager).toString())
            .setItems(arrayOf(option, "Cancelar")) { _, which ->
                if (which == 0) {
                    setHidden(pkg, !isHidden)
                    Toast.makeText(
                        this,
                        if (isHidden) "Ahora aparece en inicio" else "Quitada de inicio",
                        Toast.LENGTH_SHORT
                    ).show()
                    when (mode) {
                        Mode.HOME -> refreshHome()
                        Mode.APPS -> showApps()
                        Mode.LIVE -> {}
                    }
                }
            }
            .show()
    }

    // ---------- Utilidades ----------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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

    private fun appBanner(pkg: String): Drawable? = try {
        val intent = launchIntent(pkg)
        (intent?.let { packageManager.getActivityBanner(it) })
            ?: packageManager.getApplicationBanner(pkg)
    } catch (e: Exception) {
        null
    }

    private fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    private fun buildCards() {
        val row = findViewById<LinearLayout>(R.id.cardsRow)
        row.removeAllViews()
        val shown = mutableSetOf<String>()
        for (candidates in cardApps) {
            val pkg = installed(candidates) ?: continue
            if (!shown.add(pkg)) continue
            val card = layoutInflater.inflate(R.layout.item_card, row, false)
            val image = card.findViewById<ImageView>(R.id.cardImage)
            val banner = appBanner(pkg)
            if (banner != null) {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setImageDrawable(banner)
            } else {
                val p = dp(24)
                image.setPadding(p, p, p, p)
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                image.setImageDrawable(appIcon(pkg))
            }
            card.setOnClickListener { openPackage(pkg) }
            row.addView(card)
        }
    }

    // ---------- Adaptadores ----------

    private inner class AppsAdapter(private val items: List<ResolveInfo>) :
        RecyclerView.Adapter<AppsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.appImage)
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
                if (hasFocus) appName.text = info.loadLabel(pm)
            }
        }

        override fun getItemCount() = items.size
    }

    private inner class ChannelsAdapter(private val items: List<Channel>) :
        RecyclerView.Adapter<ChannelsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.channelLogo)
            val title: TextView = v.findViewById(R.id.channelTitle)
            val program: TextView = v.findViewById(R.id.channelProgram)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            holder.title.text =
                if (ch.number.isNotEmpty()) "${ch.number}  ${ch.name}" else ch.name
            holder.program.text = ch.program ?: ""
            holder.logo.setImageDrawable(null)
            holder.logo.tag = ch.id

            io.execute {
                val bmp = try {
                    contentResolver.openInputStream(TvContract.buildChannelLogoUri(ch.id))
                        ?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) {
                    holder.logo.post {
                        if (holder.logo.tag == ch.id) holder.logo.setImageBitmap(bmp)
                    }
                }
            }

            holder.itemView.setOnClickListener {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(ch.id))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    openLiveTv()
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
