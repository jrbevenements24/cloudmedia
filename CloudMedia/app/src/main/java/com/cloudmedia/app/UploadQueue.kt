package com.cloudmedia.app

import android.content.Context
import org.json.JSONArray

/**
 * File d'attente persistante des médias à renvoyer (mis de côté en 4G,
 * ou en échec). On stocke les IDs MediaStore ; les médias sont retrouvés
 * dans la liste chargée par l'app.
 */
class UploadQueue(context: Context) {

    private val prefs = context.getSharedPreferences("cloudmedia_queue", Context.MODE_PRIVATE)
    private val KEY = "pending_ids"

    fun ids(): MutableSet<Long> {
        val out = mutableSetOf<Long>()
        try {
            val arr = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until arr.length()) out.add(arr.getLong(i))
        } catch (_: Exception) {}
        return out
    }

    fun add(id: Long) {
        val s = ids(); s.add(id); save(s)
    }

    fun addAll(newIds: Collection<Long>) {
        val s = ids(); s.addAll(newIds); save(s)
    }

    fun remove(id: Long) {
        val s = ids(); s.remove(id); save(s)
    }

    fun clear() = save(mutableSetOf())

    fun count(): Int = ids().size

    private fun save(s: Set<Long>) {
        val arr = JSONArray(); s.forEach { arr.put(it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
