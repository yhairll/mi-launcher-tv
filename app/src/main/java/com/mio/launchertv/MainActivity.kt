package com.mio.launchertv

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    // Orden de "Apps favoritas". Solo salen las que esten instaladas.
    private val favoritePackages =
        netflix + prime + youtube + youtubeMusic + hbo + disney + spotify + playStore

    // Tarjetas de la fila de abajo
    private val cardApps = listOf(hbo, disney, youtube, netflix, prime, spotify)

    // App de TV en vivo (canales sincronizados y guia). Se usa la primera instalada.
    // Si "En vivo" no abre nada, agrega aqui el paquete de la app de TV de tu Hyundai.
    private val liveTvPackages = listOf(
        "com.google.android.tv",
        "com.mediatek.wwtv.tvcenter",
        "com.android.tv"
    )
    // =====================================

    private lateinit var appsRow: RecyclerView
    private lateinit var rowTitle: TextView
    private lateinit var appName: TextView
    private var showAll = false
    private var selectedTab: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appsRow = findViewById(R.id.appsRow)
        rowTitle = findViewById(R.id.rowTitle)
        appName = findViewById(R.id.appName)
        appsRow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Barra superior
        click(R.id.search) { openPackage("com.google.android.katniss") }
        click(R.id.iconSettings) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        click(R.id.iconBell) { }
        click(R.id.profile) { }

        // Pestañas
        selectTab(findViewById(R.id.tabHome))
        click(R.id.tabHome) {
            selectTab(findViewById(R.id.tabHome))
            showAll = false
            refreshApps()
        }
        click(R.id.tabLive) { openLiveTv() }
        click(R.id.tabShop) { openFirst(playStore) }
        click(R.id.tabApps) {
            selectTab(findViewById(R.id.tabApps))
            showAll = true
            refreshApps()
        }

        // Botones de categorias: cambia lo que abre cada uno
        click(R.id.chipLive) { openLiveTv() }
        click(R.id.chipTop) { openFirst(playStore) }
        click(R.id.chipPopular) { openFirst(youtube) }
        click(R.id.chipTrending) { openFirst(youtube) }
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        buildCards()
    }

    private fun selectTab(tab: View) {
        selectedTab?.isSelected = false
        tab.isSelected = true
        selectedTab = tab
    }

    private fun refreshApps() {
        rowTitle.setText(if (showAll) R.string.all_apps else R.string.favorites)
        appName.text = ""
        appsRow.adapter = AppsAdapter(loadApps(onlyFavorites = !showAll))
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

    // Abre la TV en vivo del televisor: primero el acceso estandar de Android TV
    // a los canales sincronizados, y si no responde, la app de TV instalada.
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
                val p = (24 * resources.displayMetrics.density).toInt()
                image.setPadding(p, p, p, p)
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                image.setImageDrawable(appIcon(pkg))
            }
            card.setOnClickListener { openPackage(pkg) }
            row.addView(card)
        }
    }

    private fun loadApps(onlyFavorites: Boolean): List<ResolveInfo> {
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val normal = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = (packageManager.queryIntentActivities(leanback, 0) +
            packageManager.queryIntentActivities(normal, 0))
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .filter { !onlyFavorites || it.activityInfo.packageName in favoritePackages }
        return all.sortedWith(
            compareBy<ResolveInfo> {
                val i = favoritePackages.indexOf(it.activityInfo.packageName)
                if (i >= 0) i else Int.MAX_VALUE
            }.thenBy { it.loadLabel(packageManager).toString().lowercase() }
        )
    }

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
                val p = (10 * resources.displayMetrics.density).toInt()
                holder.image.setPadding(p, p, p, p)
                holder.image.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.image.setImageDrawable(info.loadIcon(pm))
            }

            holder.itemView.setOnClickListener {
                openPackage(info.activityInfo.packageName)
            }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                val s = if (hasFocus) 1.15f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
                if (hasFocus) appName.text = info.loadLabel(pm)
            }
        }

        override fun getItemCount() = items.size
    }
}
