/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.data.ApiServerConfig
import com.google.ai.edge.gallery.server.ApiServerManager
import com.google.ai.edge.gallery.server.ApiServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ApiServerSettingsViewModel @Inject constructor(
  private val manager: ApiServerManager,
) : ViewModel() {
  val config = manager.config
  val status = manager.status
  val serverInfo = manager.serverInfo

  fun save(config: ApiServerConfig) = manager.saveConfig(config)
  fun start() = manager.startServer()
  fun stop() = manager.stopServer()
  fun generateKey(): String = ApiServerManager.generateApiKey()
}

@Composable
fun ApiServerSettingsScreen(
  onBack: () -> Unit,
  onOpenLogs: () -> Unit,
  viewModel: ApiServerSettingsViewModel = hiltViewModel(),
) {
  val config by viewModel.config.collectAsState()
  val status by viewModel.status.collectAsState()
  val serverInfo by viewModel.serverInfo.collectAsState()
  var host by remember(config.host) { mutableStateOf(config.host) }
  var port by remember(config.port) { mutableStateOf(config.port.toString()) }
  var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
  var authenticationEnabled by remember(config.authenticationEnabled) {
    mutableStateOf(config.authenticationEnabled)
  }

  Column(
    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("本地 API 服务", style = MaterialTheme.typography.headlineSmall)
    Text(
      when (status) {
        ApiServerStatus.Stopped -> "服务状态：已停止"
        is ApiServerStatus.Starting -> "服务状态：正在启动"
        is ApiServerStatus.Running -> "服务状态：运行中（${(status as ApiServerStatus.Running).address}）"
        is ApiServerStatus.Error -> "服务错误：${(status as ApiServerStatus.Error).message}"
      },
      style = MaterialTheme.typography.bodyMedium,
    )

    serverInfo?.let { info ->
      Text(
        "运行时间：${info.uptimeMillis / 1000}s    当前连接：${info.activeConnections}",
        style = MaterialTheme.typography.bodySmall,
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("启用 API 服务", style = MaterialTheme.typography.titleMedium)
        Text("开启后，同一局域网内的设备可以连接手机。")
      }
      Switch(
        checked = status is ApiServerStatus.Running,
        onCheckedChange = { enabled -> if (enabled) viewModel.start() else viewModel.stop() },
      )
    }

    OutlinedTextField(
      value = host,
      onValueChange = { host = it },
      label = { Text("监听地址") },
      supportingText = { Text("0.0.0.0 表示允许局域网设备连接") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    OutlinedTextField(
      value = port,
      onValueChange = { port = it.filter(Char::isDigit).take(5) },
      label = { Text("端口") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("启用 Bearer API Key 认证")
      Switch(
        checked = authenticationEnabled,
        onCheckedChange = { authenticationEnabled = it },
      )
    }
    OutlinedTextField(
      value = apiKey,
      onValueChange = { apiKey = it },
      label = { Text("API Key") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    Button(
      onClick = { apiKey = viewModel.generateKey() },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("生成新的 API Key")
    }
    Button(
      onClick = {
        val parsedPort = port.toIntOrNull()
        if (parsedPort != null && parsedPort in 1..65535 && host.isNotBlank()) {
          viewModel.save(
            config.copy(
              host = host.trim(),
              port = parsedPort,
              authenticationEnabled = authenticationEnabled,
              apiKey = apiKey.trim(),
            )
          )
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("保存配置")
    }
    Text(
      "API 地址： http://手机局域网IP:${config.port}/v1",
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      "保存监听地址或端口后，如果服务正在运行，会自动重启服务。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) { Text("查看 API 请求日志") }
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
  }
}
