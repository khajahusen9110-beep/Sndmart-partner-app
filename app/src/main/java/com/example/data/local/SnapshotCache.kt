package com.example.data.local

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import java.lang.reflect.Type

/**
 * Last known server data kept on disk (one small JSON file per list), so the app shows yesterday's numbers the moment it opens
 * and the fresh ones replace them a second later. A damaged or old file is simply ignored: the cache can never break the app.
 * Everything is stored per user and wiped on logout.
 */
class SnapshotCache(context: Context, private val moshi: Moshi) {
    private val dir = File(context.applicationContext.filesDir, "snapshots").apply { mkdirs() }
    private fun file(userId: String, key: String) = File(dir, "${userId.filter { it.isLetterOrDigit() || it == '-' }}_$key.json")

    fun <T> save(userId: String, key: String, type: Type, value: T) {
        try {
            val json = moshi.adapter<T>(type).toJson(value)
            val tmp = File(dir, "${file(userId, key).name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file(userId, key))) { file(userId, key).writeText(json); tmp.delete() }
        } catch (e: Exception) {
            Log.w("SnapshotCache", "save $key failed: ${e.message}")
        }
    }

    fun <T> load(userId: String, key: String, type: Type): T? = try {
        file(userId, key).takeIf { it.exists() }?.readText()?.let { moshi.adapter<T>(type).fromJson(it) }
    } catch (e: Exception) {
        Log.w("SnapshotCache", "load $key failed: ${e.message}")
        null
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        fun listOf(moshi: Moshi, elem: Class<*>): Type = Types.newParameterizedType(List::class.java, elem)
    }
}
