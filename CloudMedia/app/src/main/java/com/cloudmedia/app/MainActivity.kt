package com.cloudmedia.app

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // ===== SEULE ADRESSE EMBARQUÉE : le fichier de config =====
    private val configUrl = "https://jrb-evenements.synology.me/cfg-9f3a7k2x/config.json"
    // ==========================================================

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)   // gros fichiers vidéo
        .build()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("cloudmedia", MODE_PRIVATE) }
    private val queue by lazy { UploadQueue(this) }

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: MediaAdapter
    private lateinit var selCount: TextView
    private lateinit var selSize: TextView
    private lateinit var devName: TextView
    private lateinit var totalCnt: TextView
    private lateinit var btnSend: Button
    private lateinit var btnDel: Button
    private lateinit var progress: ProgressBar
    private lateinit var netState: TextView
    private lateinit var queueBanner: TextView

    private var allMedia: List<MediaItem> = emptyList()
    private var filter = "all"      // all | photo | video
    private var optDeleteAfter = false

    // config chargée
    private var cfgUploadUrl: String? = null
    private var cfgToken: String? = null
    private val accountUid by lazy { prefs.getString("account_uid", "") ?: "" }

    // suppression en attente (après envoi ou directe)
    private var pendingDeleteUris: List<Uri> = emptyList()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) loadMedia()
        else toast("Permission refusée — impossible de lire les médias.")
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            toast("Supprimés du téléphone")
            loadMedia()
        } else {
            toast("Suppression annulée")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pas de compte connecté ? → écran de connexion
        if ((prefs.getString("account_uid", null) ?: "").isEmpty()) {
            startActivity(android.content.Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recycler)
        selCount = findViewById(R.id.selCount)
        netState = findViewById(R.id.netState)
        queueBanner = findViewById(R.id.queueBanner)
        // Appui long sur l'indicateur réseau = se déconnecter du compte
        netState.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Se déconnecter ?")
                .setMessage("Compte : ${prefs.getString("account_email", "")}")
                .setPositiveButton("Déconnexion") { _, _ ->
                    prefs.edit().remove("account_uid").remove("account_email").apply()
                    startActivity(android.content.Intent(this, LoginActivity::class.java))
                    finish()
                }
                .setNegativeButton("Annuler", null)
                .show()
            true
        }
        selSize  = findViewById(R.id.selSize)
        devName  = findViewById(R.id.devName)
        totalCnt = findViewById(R.id.totalCnt)
        btnSend  = findViewById(R.id.btnSend)
        btnDel   = findViewById(R.id.btnDel)
        progress = findViewById(R.id.progress)

        adapter = MediaAdapter { updateSelection() }
        val glm = GridLayoutManager(this, 3)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (adapter.rowAt(position) is DateHeader) 3 else 1
        }
        recycler.layoutManager = glm
        recycler.adapter = adapter

        devName.text = currentDeviceName()
        findViewById<TextView>(R.id.devEdit).setOnClickListener { editDeviceName() }

        // onglets
        val tabAll = findViewById<TextView>(R.id.tabAll)
        val tabPhoto = findViewById<TextView>(R.id.tabPhoto)
        val tabVideo = findViewById<TextView>(R.id.tabVideo)
        val tabs = listOf(tabAll to "all", tabPhoto to "photo", tabVideo to "video")
        tabs.forEach { (tv, f) ->
            tv.setOnClickListener {
                filter = f
                tabs.forEach { (t, _) -> t.isSelected = (t == tv) }
                rebuild()
            }
        }
        tabAll.isSelected = true

        findViewById<TextView>(R.id.selAll).setOnClickListener { toggleSelectAll() }

        val optRow = findViewById<android.view.View>(R.id.optDelete)
        val optBox = findViewById<TextView>(R.id.optBox)
        optRow.setOnClickListener {
            optDeleteAfter = !optDeleteAfter
            optBox.text = if (optDeleteAfter) "\u2713" else ""
            optBox.isSelected = optDeleteAfter
        }

        btnSend.setOnClickListener { sendSelection() }
        btnDel.setOnClickListener { deleteSelection() }

        askPermissions()
        checkForUpdate()
    }

    private fun askPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        else
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

        val granted = perms.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) loadMedia() else permLauncher.launch(perms)
    }

    // ---------- chargement des médias ----------
    private fun loadMedia() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { queryMedia() }
            allMedia = list
            // marque visuellement les médias en attente (badge rouge réutilisé comme "en attente")
            val pending = queue.ids()
            allMedia.forEach { if (it.id in pending && !it.uploaded) it.failed = true }
            rebuild()
            updateQueueBanner()
            flushQueueIfWifi()
        }
    }

    private fun queryMedia(): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DURATION
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                out.add(
                    MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "media_$id",
                        isVideo = isVideo,
                        size = c.getLong(sizeCol),
                        durationMs = if (isVideo) c.getLong(durCol) else 0L,
                        dateAddedSec = c.getLong(dateCol),
                        uploaded = prefs.getBoolean("up_$id", false)
                    )
                )
            }
        }
        return out
    }

    // ---------- construction de la liste affichée (groupée par date) ----------
    private fun rebuild() {
        val filtered = allMedia.filter {
            filter == "all" || (filter == "photo" && !it.isVideo) || (filter == "video" && it.isVideo)
        }
        val rows = mutableListOf<GalleryRow>()
        var lastLabel: String? = null
        for (m in filtered) {
            val label = dateLabel(m.dateAddedSec)
            if (label != lastLabel) { rows.add(DateHeader(label)); lastLabel = label }
            rows.add(m)
        }
        adapter.submit(rows)
        totalCnt.text = "${filtered.size} éléments"
        updateSelection()
    }

    private fun dateLabel(sec: Long): String {
        val now = Calendar.getInstance()
        val d = Calendar.getInstance().apply { timeInMillis = sec * 1000L }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (sameDay(now, d)) return "Aujourd'hui"
        val y = now.clone() as Calendar; y.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(y, d)) return "Hier"
        val months = arrayOf("janv.","févr.","mars","avr.","mai","juin",
            "juil.","août","sept.","oct.","nov.","déc.")
        return "${d.get(Calendar.DAY_OF_MONTH)} ${months[d.get(Calendar.MONTH)]} ${d.get(Calendar.YEAR)}"
    }

    // ---------- sélection ----------
    private fun selected() = allMedia.filter { it.selected }

    private fun updateSelection() {
        val sel = selected()
        selCount.text = if (sel.isEmpty()) "Aucune sélection"
            else "${sel.size} média${if (sel.size > 1) "s" else ""} sélectionné${if (sel.size > 1) "s" else ""}"
        selSize.text = if (sel.isEmpty()) "" else fmtSize(sel.sumOf { it.size })
        btnSend.isEnabled = sel.isNotEmpty()
        btnDel.isEnabled = sel.isNotEmpty()
    }

    private fun toggleSelectAll() {
        val visible = allMedia.filter {
            filter == "all" || (filter == "photo" && !it.isVideo) || (filter == "video" && it.isVideo)
        }
        val allSel = visible.isNotEmpty() && visible.all { it.selected }
        visible.forEach { it.selected = !allSel }
        adapter.notifyDataSetChanged()
        updateSelection()
    }

    // ---------- envoi ----------
    private val LIMITE_MOBILE = 10L * 1024 * 1024  // 10 Mo en 4G/5G

    private fun sendSelection() {
        val sel = selected()
        if (sel.isEmpty()) return
        processUploads(sel, manuel = true)
    }

    /**
     * Envoie une liste de médias.
     * En 4G/5G : photos <= 10 Mo partent ; vidéos et fichiers > 10 Mo -> file d'attente.
     * En WiFi : tout part.
     * Les échecs rejoignent la file d'attente pour réessai.
     */
    private fun processUploads(items: List<MediaItem>, manuel: Boolean) {
        if (items.isEmpty()) return
        val device = currentDeviceName()
        val wifi = Net.isWifi(this)
        btnSend.isEnabled = false
        btnDel.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        progress.max = items.size
        progress.progress = 0

        scope.launch {
            if (cfgUploadUrl == null) {
                val ok = withContext(Dispatchers.IO) { loadConfig() }
                if (!ok) {
                    toast("Impossible de lire la config (vérifie le lien / la connexion).")
                    progress.visibility = android.view.View.GONE
                    btnSend.isEnabled = true; btnDel.isEnabled = true
                    return@launch
                }
            }
            var sent = 0; var skipped = 0; var failed = 0; var queued = 0
            var firstError: String? = null
            val uploadedUris = mutableListOf<Uri>()

            for ((i, m) in items.withIndex()) {
                progress.progress = i
                val key = "up_" + m.id

                if (prefs.getBoolean(key, false)) {
                    skipped++; uploadedUris.add(m.uri); queue.remove(m.id)
                    continue
                }

                // Règle réseau : en mobile, on met de côté vidéos et gros fichiers
                if (!wifi && (m.isVideo || m.size > LIMITE_MOBILE)) {
                    queue.add(m.id); m.failed = false; queued++
                    adapter.notifyDataSetChanged()
                    selCount.text = "Tri… ${i + 1}/${items.size}  \u2713 $sent  \u23f8 $queued en attente"
                    continue
                }

                val res = withContext(Dispatchers.IO) {
                    uploadOne(m, cfgUploadUrl!!, cfgToken ?: "", device)
                }
                if (res.ok) {
                    prefs.edit().putBoolean(key, true).apply()
                    m.uploaded = true; m.failed = false; m.selected = false
                    queue.remove(m.id)
                    sent++; uploadedUris.add(m.uri)
                } else {
                    m.failed = true
                    queue.add(m.id)   // échec -> file d'attente pour réessai
                    failed++; if (firstError == null) firstError = "Fichier : ${m.name}\n${res.detail}"
                }
                adapter.notifyDataSetChanged()
                selCount.text = "Envoi… ${i + 1}/${items.size}  \u2713 $sent  \u2717 $failed  \u23f8 $queued"
            }
            progress.progress = items.size
            progress.visibility = android.view.View.GONE

            val resume = buildString {
                append("Terminé : $sent envoyé(s)")
                if (skipped > 0) append(", $skipped déjà là")
                if (failed > 0) append(", $failed échec(s)")
                if (queued > 0) append(", $queued en attente de WiFi")
            }
            selCount.text = resume
            updateQueueBanner()

            if (failed > 0 && firstError != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Détail du 1er échec")
                    .setMessage(firstError)
                    .setPositiveButton("OK", null)
                    .show()
            }

            if (optDeleteAfter && uploadedUris.isNotEmpty()) {
                requestDelete(uploadedUris)
            } else {
                btnSend.isEnabled = true; btnDel.isEnabled = true
            }
        }
    }

    /** Vide la file d'attente automatiquement si on est en WiFi. */
    private fun flushQueueIfWifi() {
        if (!Net.isWifi(this)) { updateQueueBanner(); return }
        val pending = queue.ids()
        if (pending.isEmpty()) { updateQueueBanner(); return }
        val items = allMedia.filter { it.id in pending && !it.uploaded }
        if (items.isEmpty()) { updateQueueBanner(); return }
        toast("WiFi détecté — envoi des ${items.size} média(s) en attente")
        processUploads(items, manuel = false)
    }

    private fun updateQueueBanner() {
        val n = queue.count()
        val wifi = Net.isWifi(this)
        netState.text = if (wifi) "WiFi" else "4G/5G"
        if (n > 0) {
            queueBanner.visibility = android.view.View.VISIBLE
            queueBanner.text = if (wifi)
                "$n média(s) en attente — envoi en cours…"
            else
                "$n média(s) en attente de WiFi (vidéos / gros fichiers)"
        } else {
            queueBanner.visibility = android.view.View.GONE
        }
    }

    private fun loadConfig(): Boolean {
        return try {
            val req = Request.Builder().url(configUrl).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val json = JSONObject(resp.body?.string() ?: return false)
                cfgUploadUrl = json.getString("upload_url")
                cfgToken = json.optString("token", "")
                true
            }
        } catch (e: Exception) { false }
    }

    private fun uploadOne(m: MediaItem, url: String, token: String, device: String): UploadResult {
        return try {
            val tmp = File(cacheDir, m.name)
            contentResolver.openInputStream(m.uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            } ?: return UploadResult(false, "Lecture du fichier impossible sur le téléphone")

            val mime = if (m.isVideo) "video/*" else "image/*"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("token", token)
                .addFormDataPart("uid", accountUid)
                .addFormDataPart("device", device)
                .addFormDataPart("media", m.name, tmp.asRequestBody(mime.toMediaTypeOrNull()))
                .build()
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { resp ->
                tmp.delete()
                val bodyStr = (resp.body?.string() ?: "").take(400)
                UploadResult(resp.isSuccessful, "Code HTTP ${resp.code}\nRéponse serveur :\n$bodyStr")
            }
        } catch (e: Exception) {
            UploadResult(false, "Erreur réseau : ${e.javaClass.simpleName}\n${e.message ?: ""}")
        }
    }

    // ---------- suppression ----------
    private fun deleteSelection() {
        val uris = selected().map { it.uri }
        if (uris.isEmpty()) return
        requestDelete(uris)
    }

    private fun requestDelete(uris: List<Uri>) {
        pendingDeleteUris = uris
        val pi = MediaStore.createDeleteRequest(contentResolver, uris)
        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    // ---------- nom d'appareil ----------
    private fun currentDeviceName(): String {
        prefs.getString("device_name", null)?.let { return it }
        val raw = "${Build.MANUFACTURER}-${Build.MODEL}"
        val clean = raw.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifEmpty { "telephone" }
        prefs.edit().putString("device_name", clean).apply()
        return clean
    }

    private fun editDeviceName() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(currentDeviceName())
        }
        AlertDialog.Builder(this)
            .setTitle("Nom du dossier de ce téléphone")
            .setMessage("Les médias de ce téléphone iront dans ce dossier sur le serveur.")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-')
                if (v.isNotEmpty()) {
                    prefs.edit().putString("device_name", v).apply()
                    devName.text = v
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- utils ----------
    private fun fmtSize(bytes: Long): String {
        val mo = bytes / (1024.0 * 1024.0)
        return if (mo >= 1024) "%.2f Go".format(mo / 1024) else "%.0f Mo".format(mo)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- mise à jour de l'app ----------
    private fun currentVersionCode(): Int {
        return try {
            val p = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
        } catch (e: Exception) { 1 }
    }

    private fun checkForUpdate() {
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(configUrl).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        JSONObject(resp.body?.string() ?: return@withContext null)
                    }
                } catch (e: Exception) { null }
            } ?: return@launch

            val latest = info.optInt("latest_version_code", 0)
            val apkUrl = info.optString("apk_url", "")
            val notes  = info.optString("update_notes", "")
            val name   = info.optString("latest_version_name", "")

            if (latest > currentVersionCode() && apkUrl.isNotEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Mise à jour disponible" + if (name.isNotEmpty()) " ($name)" else "")
                    .setMessage(if (notes.isNotEmpty()) notes else "Une nouvelle version de Cloud Media est disponible. L'installer ?")
                    .setPositiveButton("Mettre à jour") { _, _ -> ensureInstallPermissionThen(apkUrl) }
                    .setNegativeButton("Plus tard", null)
                    .show()
            }
        }
    }

    private fun ensureInstallPermissionThen(apkUrl: String) {
        // Android 8+ : l'app doit être autorisée à installer des paquets
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Autorisation requise")
                .setMessage("Pour installer la mise à jour, autorise Cloud Media à installer des applications, puis relance la mise à jour.")
                .setPositiveButton("Ouvrir les réglages") { _, _ ->
                    val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    startActivity(i)
                }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        downloadAndInstall(apkUrl)
    }

    private fun downloadAndInstall(apkUrl: String) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Téléchargement…")
            .setMessage("Récupération de la nouvelle version, patiente.")
            .setCancelable(false)
            .create()
        dlg.show()

        scope.launch {
            val apk = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(apkUrl).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        val file = File(cacheDir, "update.apk")
                        resp.body?.byteStream()?.use { input ->
                            FileOutputStream(file).use { out -> input.copyTo(out) }
                        } ?: return@withContext null
                        file
                    }
                } catch (e: Exception) { null }
            }
            dlg.dismiss()
            if (apk == null) {
                toast("Échec du téléchargement de la mise à jour.")
                return@launch
            }
            // Lancer l'installation (Android prend le relais avec sa propre confirmation)
            try {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", apk)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                toast("Impossible de lancer l'installation : ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::netState.isInitialized) {
            updateQueueBanner()
            flushQueueIfWifi()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
