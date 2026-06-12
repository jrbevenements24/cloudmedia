package com.cloudmedia.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    // ===== Adresse de connexion (endpoint serveur) =====
    private val loginUrl = "https://jrb-evenements.synology.me/cloudmedia/web/app_login.php"
    // ===================================================

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("cloudmedia", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Déjà connecté ? → on va direct à la galerie
        if (!prefs.getString("account_uid", null).isNullOrEmpty()) {
            goToMain(); return
        }
        setContentView(R.layout.activity_login)

        val email = findViewById<EditText>(R.id.email)
        val mdp   = findViewById<EditText>(R.id.motdepasse)
        val btn   = findViewById<Button>(R.id.btnLogin)
        val status= findViewById<TextView>(R.id.status)

        btn.setOnClickListener {
            val e = email.text.toString().trim()
            val p = mdp.text.toString()
            if (e.isEmpty() || p.isEmpty()) { status.text = "Renseigne ton mail et ton mot de passe."; return@setOnClickListener }
            btn.isEnabled = false
            status.text = "Connexion…"
            scope.launch {
                val res = withContext(Dispatchers.IO) { doLogin(e, p) }
                btn.isEnabled = true
                if (res != null) {
                    prefs.edit()
                        .putString("account_uid", res.first)
                        .putString("account_email", res.second)
                        .apply()
                    goToMain()
                } else {
                    status.text = "Mail ou mot de passe incorrect."
                }
            }
        }
    }

    /** Retourne (uid, email) si OK, sinon null. */
    private fun doLogin(email: String, mdp: String): Pair<String, String>? {
        return try {
            val body = FormBody.Builder()
                .add("email", email)
                .add("motdepasse", mdp)
                .build()
            val req = Request.Builder().url(loginUrl).post(body).build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: return null)
                if (json.optBoolean("ok", false)) {
                    Pair(json.getString("uid"), json.optString("email", email))
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
