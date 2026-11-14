/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.data

import androidx.core.content.edit

/** Persistent configuration for the LAN OpenAI-compatible API. */
data class ApiServerConfig(
  val enabled: Boolean = false,
  // Bind to all interfaces so other devices on the same LAN can connect.
  val host: String = "0.0.0.0",
  val port: Int = 8080,
  val authenticationEnabled: Boolean = true,
  val apiKey: String = "",
  val maxConcurrent: Int = 1,
  val queueSize: Int = 10,
  val requestTimeoutMs: Long = 180_000L,
  val defaultModelId: String = "",
  val defaultTemperature: Double = 0.7,
  val defaultMaxTokens: Int = 1024,
  val defaultTopP: Double = 0.95,
  val defaultTopK: Int = 40,
  val defaultAccelerator: String = Accelerator.GPU.label,
  val defaultVisionAccelerator: String = Accelerator.GPU.label,
) {
  fun validate(): ApiServerConfig {
    require(host.isNotBlank()) { "Listen address cannot be empty." }
    require(port in 1..65535) { "Port must be between 1 and 65535." }
    require(maxConcurrent in 1..16) { "Maximum concurrent requests must be between 1 and 16." }
    require(queueSize in 0..256) { "Queue size must be between 0 and 256." }
    require(requestTimeoutMs >= 1_000L) { "Request timeout must be at least 1 second." }
    require(defaultTemperature in 0.0..2.0) { "Temperature must be between 0 and 2." }
    require(defaultMaxTokens > 0) { "Maximum output tokens must be positive." }
    require(defaultTopP in 0.0..1.0) { "Top P must be between 0 and 1." }
    require(defaultTopK > 0) { "Top K must be positive." }
    if (authenticationEnabled) require(apiKey.isNotBlank()) { "API key is required when authentication is enabled." }
    return this
  }
}

/** SharedPreferences-backed configuration, following the original Local API project. */
class ApiServerConfigManager(context: android.content.Context) {
  private val preferences = context.getSharedPreferences("api_server_config", android.content.Context.MODE_PRIVATE)

  private val _configFlow = kotlinx.coroutines.flow.MutableStateFlow(loadConfig())
  val configFlow: kotlinx.coroutines.flow.StateFlow<ApiServerConfig> = _configFlow

  @Synchronized
  fun saveConfig(config: ApiServerConfig) {
    val valid = config.copy(enabled = _configFlow.value.enabled).validate()
    preferences.edit {
      putString("host", valid.host)
      putInt("port", valid.port)
      putBoolean("authentication_enabled", valid.authenticationEnabled)
      putString("api_key", valid.apiKey)
      putInt("max_concurrent", valid.maxConcurrent)
      putInt("queue_size", valid.queueSize)
      putLong("request_timeout_ms", valid.requestTimeoutMs)
      putString("default_model_id", valid.defaultModelId)
      putString("default_temperature", valid.defaultTemperature.toString())
      putInt("default_max_tokens", valid.defaultMaxTokens)
      putString("default_top_p", valid.defaultTopP.toString())
      putInt("default_top_k", valid.defaultTopK)
      putString("default_accelerator", valid.defaultAccelerator)
      putString("default_vision_accelerator", valid.defaultVisionAccelerator)
    }
    /* old edit body removed */
    /*
    preferences.edit()
      .putString("host", valid.host)
      .putInt("port", valid.port)
      .putBoolean("authentication_enabled", valid.authenticationEnabled)
      .putString("api_key", valid.apiKey)
      .putInt("max_concurrent", valid.maxConcurrent)
      .putInt("queue_size", valid.queueSize)
      .putLong("request_timeout_ms", valid.requestTimeoutMs)
      .putString("default_model_id", valid.defaultModelId)
      .putString("default_temperature", valid.defaultTemperature.toString())
      .putInt("default_max_tokens", valid.defaultMaxTokens)
      .putString("default_top_p", valid.defaultTopP.toString())
      .putInt("default_top_k", valid.defaultTopK)
      .putString("default_accelerator", valid.defaultAccelerator)
      .putString("default_vision_accelerator", valid.defaultVisionAccelerator)
      .apply()
    */
    _configFlow.value = valid
  }

  @Synchronized
  fun setEnabled(enabled: Boolean) {
    preferences.edit().putBoolean("enabled", enabled).apply()
    _configFlow.value = _configFlow.value.copy(enabled = enabled)
  }

  private fun loadConfig(): ApiServerConfig {
    val defaults = ApiServerConfig()
    return ApiServerConfig(
      enabled = preferences.getBoolean("enabled", false),
      host = preferences.getString("host", defaults.host) ?: defaults.host,
      port = preferences.getInt("port", defaults.port),
      authenticationEnabled = preferences.getBoolean("authentication_enabled", defaults.authenticationEnabled),
      apiKey = preferences.getString("api_key", defaults.apiKey) ?: defaults.apiKey,
      maxConcurrent = preferences.getInt("max_concurrent", defaults.maxConcurrent),
      queueSize = preferences.getInt("queue_size", defaults.queueSize),
      requestTimeoutMs = preferences.getLong("request_timeout_ms", defaults.requestTimeoutMs),
      defaultModelId = preferences.getString("default_model_id", defaults.defaultModelId) ?: defaults.defaultModelId,
      defaultTemperature = preferences.getString("default_temperature", null)?.toDoubleOrNull() ?: defaults.defaultTemperature,
      defaultMaxTokens = preferences.getInt("default_max_tokens", defaults.defaultMaxTokens),
      defaultTopP = preferences.getString("default_top_p", null)?.toDoubleOrNull() ?: defaults.defaultTopP,
      defaultTopK = preferences.getInt("default_top_k", defaults.defaultTopK),
      defaultAccelerator = preferences.getString("default_accelerator", defaults.defaultAccelerator) ?: defaults.defaultAccelerator,
      defaultVisionAccelerator = preferences.getString("default_vision_accelerator", defaults.defaultVisionAccelerator) ?: defaults.defaultVisionAccelerator,
    )
  }
}
