package com.mio.launchertv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.KeyEvent
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
import java.net.HttpURLConnection
import java.net.URL
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

    // Orden inicial de las apps (despues puedes moverlas tu con "Mover").
    private val favoritePackages =
        netflix + prime + youtube + youtubeMusic + hbo + disney + spotify + playStore

    // Fila de respaldo "Para ti" (se usa si Android no deja leer los recomendados)
    private val fallbackApps = listOf(hbo, disney, youtube, netflix, prime, spotify)
    // =====================================

    private enum class Mode { HOME, APPS }

    private class Program(
        val title: String,
        val imageUri: String?,
        val intentUri: String?,
        val pkg: String?,
        val image: Drawable? = null,
        val iconOnly: Boolean = false
    )

    private class Row(val title: String, val programs: List<Program>)

    private class RawProgram(val channelId: Long, val program: Program)

    private val epgPermission = "com.android.providers.tv.permission.READ_EPG_DATA"
    private val requestEpg = 41

    private lateinit var prefs: SharedPreferences
    private lateinit var appsRow: RecyclerView
    private lateinit var appName: TextView
    private lateinit var homeContent: View
    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelList: RecyclerView
    private lateinit var rowsContainer: LinearLayout

    private var mode = Mode.HOME
    private var selectedTab: View? = null
    private var homeAdapter: AppsAdapter? = null
    private var movingPkg: String? = null
    private var cachedRows: List<Row>? = null
    private var rowsLoadId = 0
    private val io = Executors.newFixedThreadPool(3)

    private val imageCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)

        appsRow = findViewById(R.id.appsRow)
        appsRow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        appName = findViewById(R.id.appName)
        homeContent = findViewById(R.id.homeContent)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        panelList = findViewById(R.id.panelList)
        rowsContainer = findViewById(R.id.rowsContainer)
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
        loadAssistantIcon()
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
            Mode.HOME -> {
                refreshHome()
                showRows(cachedRows ?: listOf(fallbackRow()))
                loadRows()
            }
            Mode.APPS -> showApps()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // En un launcher, "atras" solo vuelve al inicio; nunca cierra la pantalla.
        if (mode != Mode.HOME) showMode(Mode.HOME)
    }

    // Mientras se mueve una app, las flechas y OK controlan el movimiento.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (movingPkg != null && mode == Mode.HOME) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        moveCurrent(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) endMove()
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

    // Usa el icono del asistente que tenga instalado el propio TV.
    private fun loadAssistantIcon() {
        val icon = try {
            val ri = packageManager.resolveActivity(Intent(Intent.ACTION_ASSIST), 0)
            if (ri != null && ri.activityInfo.packageName != "android") {
                ri.loadIcon(packageManager)
            } else {
                appIcon("com.google.android.katniss")
            }
        } catch (e: Exception) {
            null
        }
        if (icon != null) findViewById<ImageView>(R.id.assistantIcon).setImageDrawable(icon)
    }

    // ---------- Pantallas ----------

    private fun showMode(newMode: Mode) {
        mode = newMode
        movingPkg = null
        val home = newMode == Mode.HOME
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

    // ---------- Recomendados (YouTube y otras apps) ----------

    private fun showRows(rows: List<Row>) {
        rowsContainer.removeAllViews()
        for (row in rows) {
            val title = TextView(this).apply {
                text = row.title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                setPadding(dp(4), dp(10), 0, 0)
            }
            val list = RecyclerView(this).apply {
                layoutManager =
                    LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                clipToPadding = false
                clipChildren = false
                setPadding(dp(4), dp(6), dp(4), dp(6))
                adapter = ProgramsAdapter(row.programs)
            }
            rowsContainer.addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            rowsContainer.addView(
                list,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128))
            )
        }
    }

    private fun loadRows() {
        val id = ++rowsLoadId
        val granted = ContextCompat.checkSelfPermission(this, epgPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.getBoolean("epgAsked", false)) {
            prefs.edit().putBoolean("epgAsked", true).apply()
            ActivityCompat.requestPermissions(this, arrayOf(epgPermission), requestEpg)
            return
        }
        Thread {
            val rows = try {
                queryRows()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (id == rowsLoadId && mode == Mode.HOME && rows.isNotEmpty()) {
                    cachedRows = rows
                    showRows(rows)
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Se intenta leer igual: si Android lo bloquea, se queda la fila de respaldo.
        if (requestCode == requestEpg && mode == Mode.HOME) loadRows()
    }

    private fun Cursor.str(column: String): String? {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getString(i) else null
    }

    private fun readPrograms(uri: Uri, sort: String?): List<RawProgram> {
        val out = mutableListOf<RawProgram>()
        contentResolver.query(uri, null, null, null, sort)?.use { c ->
            val iChannel = c.getColumnIndex("channel_id")
            while (c.moveToNext() && out.size < 300) {
                val image = c.str("thumbnail_uri") ?: c.str("poster_art_uri")
                val program = Program(
                    title = c.str("title") ?: "",
                    imageUri = image,
                    intentUri = c.str("intent_uri"),
                    pkg = c.str("package_name")
                )
                val channel = if (iChannel >= 0 && !c.isNull(iChannel)) c.getLong(iChannel) else -1L
                out.add(RawProgram(channel, program))
            }
        }
        return out
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (e: Exception) {
        pkg
    }

    // Lee los "canales de inicio" que publican YouTube y otras apps (Android 8+).
    private fun queryRows(): List<Row> {
        if (Build.VERSION.SDK_INT < 26) return emptyList()
        val rows = mutableListOf<Row>()

        // Seguir viendo
        try {
            val watchNext = readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, null)
                .map { it.program }
                .take(20)
            if (watchNext.isNotEmpty()) rows.add(Row("Continuar viendo", watchNext))
        } catch (e: Exception) {
            // no disponible
        }

        // Filas de cada app (YouTube: Recomendaciones, Tendencias, etc.)
        val channels = linkedMapOf<Long, String>()
        contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            val iId = c.getColumnIndex("_id")
            val iBrowsable = c.getColumnIndex("browsable")
            while (c.moveToNext()) {
                if (c.str("type") != "TYPE_PREVIEW") continue
                if (iBrowsable >= 0 && !c.isNull(iBrowsable) && c.getInt(iBrowsable) == 0) continue
                val pkg = c.str("package_name") ?: continue
                if (pkg == packageName) continue
                val name = c.str("display_name") ?: ""
                val label = appLabel(pkg)
                channels[c.getLong(iId)] = if (name.isBlank()) label else "$label: $name"
            }
        }
        if (channels.isNotEmpty()) {
            val programs = try {
                readPrograms(TvContract.PreviewPrograms.CONTENT_URI, "weight DESC")
            } catch (e: Exception) {
                readPrograms(TvContract.PreviewPrograms.CONTENT_URI, null)
            }
            val grouped = programs.groupBy({ it.channelId }, { it.program })
            for ((id, title) in channels) {
                val list = grouped[id]?.take(20) ?: continue
                if (list.isNotEmpty()) rows.add(Row(title, list))
                if (rows.size >= 8) break
            }
        }
        return rows
    }

    // Respaldo: banners de tus apps de streaming
    private fun fallbackRow(): Row {
        val programs = mutableListOf<Program>()
        val shown = mutableSetOf<String>()
        for (candidates in fallbackApps) {
            val pkg = installed(candidates) ?: continue
            if (!shown.add(pkg)) continue
            val banner = appBanner(pkg)
            programs.add(
                Program(
                    title = appLabel(pkg),
                    imageUri = null,
                    intentUri = null,
                    pkg = pkg,
                    image = banner ?: appIcon(pkg),
                    iconOnly = banner == null
                )
            )
        }
        return Row("Para ti", programs)
    }

    private fun openProgram(p: Program) {
        val uri = p.intentUri
        if (uri != null) {
            try {
                startActivity(
                    Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (e: Exception) {
                // intenta abrir la app
            }
        }
        p.pkg?.let { openPackage(it) }
    }

    private fun loadImage(target: ImageView, uriStr: String) {
        target.tag = uriStr
        val cached = imageCache.get(uriStr)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        io.execute {
            val bmp = decodeImage(uriStr)
            if (bmp != null) {
                imageCache.put(uriStr, bmp)
                target.post { if (target.tag == uriStr) target.setImageBitmap(bmp) }
            }
        }
    }

    private fun decodeImage(uriStr: String): Bitmap? = try {
        val uri = Uri.parse(uriStr)
        val bytes: ByteArray? = if (uri.scheme == "http" || uri.scheme == "https") {
            val conn = URL(uriStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } else {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (bytes == null) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    } catch (e: Exception) {
        null
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

        AlertDialog.Builder(this)
            .setTitle(info.loadLabel(packageManager).toString())
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

    // ---------- Adaptadores ----------

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
                if (hasFocus && movingPkg == null) appName.text = info.loadLabel(pm)
            }
        }

        override fun getItemCount() = items.size
    }

    private inner class ProgramsAdapter(private val items: List<Program>) :
        RecyclerView.Adapter<ProgramsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.cardImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            val img = holder.image
            img.tag = null
            val drawable = p.image
            val uri = p.imageUri
            if (drawable != null) {
                if (p.iconOnly) {
                    val pad = dp(24)
                    img.setPadding(pad, pad, pad, pad)
                    img.scaleType = ImageView.ScaleType.FIT_CENTER
                } else {
                    img.setPadding(0, 0, 0, 0)
                    img.scaleType = ImageView.ScaleType.CENTER_CROP
                }
                img.setImageDrawable(drawable)
            } else {
                img.setPadding(0, 0, 0, 0)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setImageDrawable(null)
                if (uri != null) loadImage(img, uri)
            }

            holder.itemView.setOnClickListener { openProgram(p) }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                val s = if (hasFocus) 1.06f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
        }

        override fun getItemCount() = items.size
    }
}
