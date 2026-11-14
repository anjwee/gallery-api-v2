package com.google.ai.edge.gallery.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded persistent HTTP traffic log. Sensitive headers and request bodies are not stored. */
object HttpTrafficLogger {
  data class Entry(val time: Long, val method: String, val path: String, val status: Int, val detail: String)
  private const val PREFS = "api_http_logs"
  private const val KEY = "entries"
  private const val MAX_ENTRIES = 500
  private lateinit var prefs: android.content.SharedPreferences
  private val mutableLogs = MutableStateFlow<List<Entry>>(emptyList())
  val logs = mutableLogs.asStateFlow()
  private val mutableEnabled = MutableStateFlow(true)
  val isLoggingEnabled = mutableEnabled.asStateFlow()

  @Synchronized fun initialize(context: Context) {
    prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    mutableEnabled.value = prefs.getBoolean("enabled", true)
    val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
    mutableLogs.value = (0 until array.length()).mapNotNull { i ->
      runCatching {
        val o = array.getJSONObject(i)
        Entry(o.getLong("time"), o.getString("method"), o.getString("path"), o.getInt("status"), o.optString("detail"))
      }.getOrNull()
    }
  }

  @Synchronized fun record(method: String, path: String, status: Int, elapsedMs: Long) {
    if (!mutableEnabled.value || !::prefs.isInitialized) return
    mutableLogs.value = (mutableLogs.value + Entry(System.currentTimeMillis(), method, path, status, "耗时 ${elapsedMs} ms")).takeLast(MAX_ENTRIES)
    persist()
  }

  @Synchronized fun clear() {
    mutableLogs.value = emptyList()
    if (::prefs.isInitialized) prefs.edit().remove(KEY).apply()
  }

  @Synchronized fun setEnabled(enabled: Boolean) {
    mutableEnabled.value = enabled
    if (::prefs.isInitialized) prefs.edit().putBoolean("enabled", enabled).apply()
  }

  private fun persist() {
    val array = JSONArray()
    mutableLogs.value.forEach { e ->
      array.put(JSONObject().put("time", e.time).put("method", e.method).put("path", e.path).put("status", e.status).put("detail", e.detail))
    }
    prefs.edit().putString(KEY, array.toString()).apply()
  }
}
