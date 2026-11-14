/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.ApiServerConfig
import com.google.ai.edge.gallery.data.ApiServerConfigManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ApiServerInfo(val host: String, val port: Int, val uptimeMillis: Long, val activeConnections: Int)

sealed interface ApiServerStatus {
  data object Stopped : ApiServerStatus
  data class Starting(val address: String) : ApiServerStatus
  data class Running(val address: String) : ApiServerStatus
  data class Error(val message: String) : ApiServerStatus
}

/** Process-scoped owner of the LAN API; it does not depend on a Compose screen's lifecycle. */
@Singleton
class ApiServerManager @Inject constructor(
  @ApplicationContext context: Context,
  private val gateway: ApiModelCoordinator,
) {
  private val configManager = ApiServerConfigManager(context.applicationContext)
  val config: StateFlow<ApiServerConfig> = configManager.configFlow

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  private val _status = MutableStateFlow<ApiServerStatus>(ApiServerStatus.Stopped)
  val status: StateFlow<ApiServerStatus> = _status.asStateFlow()
  private val _serverInfo = MutableStateFlow<ApiServerInfo?>(null)
  val serverInfo: StateFlow<ApiServerInfo?> = _serverInfo.asStateFlow()
  @Volatile private var monitorStarted = false

  @Volatile private var server: ApiHttpServer? = null

  fun saveConfig(newConfig: ApiServerConfig) {
    val wasRunning = isRunning()
    configManager.saveConfig(newConfig)
    if (wasRunning) {
      scope.launch {
        mutex.withLock {
          stopLocked()
          startLocked()
        }
      }
    }
  }

  fun startServer() {
    scope.launch {
      mutex.withLock { startLocked() }
    }
  }

  fun stopServer() {
    scope.launch {
      mutex.withLock { stopLocked() }
    }
  }

  fun isRunning(): Boolean = server?.isRunning() == true

  /**
   * Initializes process state without silently reopening a LAN listener after Android killed
   * the process. The persisted settings and API key remain available for the user to start again.
   */
  fun initialize() {
    val current = configManager.configFlow.value
    if (current.apiKey.isBlank()) {
      configManager.saveConfig(current.copy(apiKey = generateApiKey()))
    }
    configManager.setEnabled(false)
    _status.value = ApiServerStatus.Stopped
    _serverInfo.value = null
    if (!monitorStarted) {
      monitorStarted = true
      scope.launch {
        while (isActive) {
          refreshServerInfo()
          delay(1_000L)
        }
      }
    }
  }

  private fun startLocked() {
    if (server?.isRunning() == true) return
    val current = configManager.configFlow.value
    try {
      current.validate()
      _status.value = ApiServerStatus.Starting("${current.host}:${current.port}")
      val created = ApiHttpServer(
        configProvider = {
          val latest = configManager.configFlow.value
          ApiHttpServerConfig(
            bindAddress = latest.host,
            port = latest.port,
            apiKey = latest.apiKey,
            authenticationEnabled = latest.authenticationEnabled,
          )
        },
        gateway = gateway,
        onStateChanged = { running, detail ->
          _status.value = if (running) {
            ApiServerStatus.Running("${current.host}:${current.port}")
          } else if (detail != null) {
            ApiServerStatus.Error(detail)
          } else {
            ApiServerStatus.Stopped
          }
          if (!running) configManager.setEnabled(false)
          Log.i(TAG, "event=server_state running=$running detail=$detail")
        },
        onRequest = { method, path, code, elapsed ->
          Log.i(TAG, "event=http_request method=$method path=$path status=$code elapsed_ms=$elapsed")
        },
      )
      created.start()
      server = created
      configManager.setEnabled(true)
      _status.value = ApiServerStatus.Running("${current.host}:${current.port}")
      refreshServerInfo()
      Log.i(TAG, "event=server_started host=${current.host} port=${current.port}")
    } catch (error: Exception) {
      server = null
      configManager.setEnabled(false)
      _status.value = ApiServerStatus.Error(error.message ?: error.javaClass.simpleName)
      Log.e(TAG, "event=server_start_failed", error)
    }
  }

  private fun stopLocked() {
    runCatching { server?.stop() }
      .onFailure { Log.e(TAG, "event=server_stop_failed", it) }
    server = null
    configManager.setEnabled(false)
    _status.value = ApiServerStatus.Stopped
    _serverInfo.value = null
    Log.i(TAG, "event=server_stopped")
  }

  private fun refreshServerInfo() {
    val activeServer = server
    if (activeServer?.isRunning() == true) {
      val current = configManager.configFlow.value
      _status.value = ApiServerStatus.Running("${current.host}:${current.port}")
      _serverInfo.value = ApiServerInfo(
        host = current.host,
        port = current.port,
        uptimeMillis = activeServer.getUptimeMillis(),
        activeConnections = activeServer.getCurrentConnections(),
      )
    } else {
      _serverInfo.value = null
      if (_status.value is ApiServerStatus.Running) {
        _status.value = ApiServerStatus.Stopped
        configManager.setEnabled(false)
      }
    }
  }

  companion object {
    private const val TAG = "ApiServerManager"

    fun generateApiKey(): String {
      val bytes = ByteArray(32)
      SecureRandom().nextBytes(bytes)
      return bytes.joinToString("") { "%02x".format(it) }
    }
  }
}
