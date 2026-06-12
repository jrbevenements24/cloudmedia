package com.cloudmedia.app

import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: MediaAdapter
    private lateinit var selCount: TextView
    private lateinit var selSize: TextView
    private lateinit var devName: TextView
    private lateinit var totalCnt: TextView
    private lateinit var btnSend: Button
    private lateinit var btnDel: Button
    private lateinit var progress: ProgressBar

    private var allMedia: List<MediaItem> = emptyList()
    private var filter = "all"      // all | photo | video
    private var optDeleteAfter = false

    // config chargée
    private var cfgUploadUrl: String? = null
    private var cfgToken: String? = null

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
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recycler)
        selCount = findViewById(R.id.selCount)
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
            rebuild()
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
    private fun sendSelection() {
        val sel = selected()
        if (sel.isEmpty()) return
        val device = currentDeviceName()
        btnSend.isEnabled = false
        btnDel.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        progress.max = sel.size
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
            var sent = 0; var skipped = 0; var failed = 0
            var firstError: String? = null
            val uploadedUris = mutableListOf<Uri>()
            for ((i, m) in sel.withIndex()) {
                progress.progress = i
                val key = "up_" + m.id
                if (prefs.getBoolean(key, false)) {
                    skipped++; uploadedUris.add(m.uri)
                    selCount.text = "Envoi… ${i + 1}/${sel.size}  (déjà : $skipped)"
                    continue
                }
                val res = withContext(Dispatchers.IO) {
                    uploadOne(m, cfgUploadUrl!!, cfgToken ?: "", device)
                }
                if (res.ok) {
                    prefs.edit().putBoolean(key, true).apply()
                    m.uploaded = true; m.failed = false; m.selected = false
                    sent++; uploadedUris.add(m.uri)
                } else {
                    m.failed = true
                    failed++; if (firstError == null) firstError = "Fichier : ${m.name}\n${res.detail}"
                }
                adapter.notifyDataSetChanged()
                selCount.text = "Envoi… ${i + 1}/${sel.size}  \u2713 $sent  \u23ed $skipped  \u2717 $failed"
            }
            progress.progress = sel.size
            progress.visibility = android.view.View.GONE
            selCount.text = "Terminé : $sent envoyés, $skipped déjà là, $failed échoués"

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

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
