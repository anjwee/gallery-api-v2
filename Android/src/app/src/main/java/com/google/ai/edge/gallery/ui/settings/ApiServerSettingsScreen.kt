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
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ApiServerConfig
import com.google.ai.edge.gallery.server.ApiServerManager
import com.google.ai.edge.gallery.server.ApiServerStatus
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
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
  modelManagerViewModel: ModelManagerViewModel = hiltViewModel(),
) {
  val config by viewModel.config.collectAsState()
  val status by viewModel.status.collectAsState()
  val serverInfo by viewModel.serverInfo.collectAsState()
  val downloadedModels = modelManagerViewModel.getAllDownloadedModels()
  val selectedModel = downloadedModels.firstOrNull { it.name == config.defaultModelId }
  val textAccelerators = selectedModel?.accelerators?.ifEmpty { Accelerator.values().toList() }
    ?: Accelerator.values().toList()
  val running = status is ApiServerStatus.Running || status is ApiServerStatus.Starting

  var host by remember(config.host) { mutableStateOf(config.host) }
  var port by remember(config.port) { mutableStateOf(config.port.toString()) }
  var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
  var temperature by remember(config.defaultTemperature) { mutableStateOf(config.defaultTemperature.toString()) }
  var maxTokens by remember(config.defaultMaxTokens) { mutableStateOf(config.defaultMaxTokens.toString()) }
  var topP by remember(config.defaultTopP) { mutableStateOf(config.defaultTopP.toString()) }
  var topK by remember(config.defaultTopK) { mutableStateOf(config.defaultTopK.toString()) }
  var maxConcurrent by remember(config.maxConcurrent) { mutableStateOf(config.maxConcurrent.toString()) }
  var queueSize by remember(config.queueSize) { mutableStateOf(config.queueSize.toString()) }
  var timeout by remember(config.requestTimeoutMs) { mutableStateOf((config.requestTimeoutMs / 1000).toString()) }
  var authEnabled by remember(config.authenticationEnabled) { mutableStateOf(config.authenticationEnabled) }

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
    serverInfo?.let {
      Text("运行时间：${it.uptimeMillis / 1000}s    当前连接：${it.activeConnections}")
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("启用 API 服务", style = MaterialTheme.typography.titleMedium)
        Text("允许局域网设备调用手机上已下载的模型。")
      }
      Switch(
        checked = running,
        onCheckedChange = { if (it) viewModel.start() else viewModel.stop() },
      )
    }

    Text("默认模型", style = MaterialTheme.typography.titleMedium)
    if (downloadedModels.isEmpty()) {
      Text("暂无已下载的 LLM 模型。请先在原有 Models 页面下载模型。")
    } else {
      downloadedModels.forEach { model ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName.ifBlank { model.name })
            Text(model.name, style = MaterialTheme.typography.bodySmall)
          }
          Button(
            enabled = !running && config.defaultModelId != model.name,
            onClick = {
              val supported = model.accelerators.ifEmpty { Accelerator.values().toList() }
              viewModel.save(config.copy(
                defaultModelId = model.name,
                defaultAccelerator = supported.firstOrNull { it.label == config.defaultAccelerator }?.label
                  ?: supported.firstOrNull()?.label ?: Accelerator.GPU.label,
              ))
            },
          ) { Text(if (config.defaultModelId == model.name) "已选择" else "选择") }
        }
      }
    }

    Text("推理后端", style = MaterialTheme.typography.titleMedium)
    Text("文本推理", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      textAccelerators.forEach { accelerator ->
        Button(
          enabled = !running,
          onClick = { viewModel.save(config.copy(defaultAccelerator = accelerator.label)) },
        ) { Text(if (config.defaultAccelerator == accelerator.label) "✓ ${accelerator.label}" else accelerator.label) }
      }
    }
    Text("视觉输入", style = MaterialTheme.typography.titleSmall)
    Text("当前 API 尚未接入图像推理；此选项暂时只保存配置，不代表视觉请求已可用。", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Accelerator.values().forEach { accelerator ->
        Button(
          enabled = !running,
          onClick = { viewModel.save(config.copy(defaultVisionAccelerator = accelerator.label)) },
        ) { Text(if (config.defaultVisionAccelerator == accelerator.label) "✓ ${accelerator.label}" else accelerator.label) }
      }
    }

    Text("默认模型参数", style = MaterialTheme.typography.titleMedium)
    Text("API 请求显式传入参数时应优先使用请求值。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature (0–2)") }, enabled = !running, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(maxTokens, { maxTokens = it }, label = { Text("Max tokens") }, enabled = !running, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(topP, { topP = it }, label = { Text("Top P (0–1)") }, enabled = !running, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(topK, { topK = it }, label = { Text("Top K") }, enabled = !running, modifier = Modifier.fillMaxWidth())

    Text("服务配置", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(host, { host = it }, label = { Text("监听地址（0.0.0.0 允许局域网）") }, enabled = !running, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("端口") }, enabled = !running, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(maxConcurrent, { maxConcurrent = it.filter(Char::isDigit) }, label = { Text("最大并发请求数 (1–16)") }, enabled = !running, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(queueSize, { queueSize = it.filter(Char::isDigit) }, label = { Text("等待队列大小 (0–256)") }, enabled = !running, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit) }, label = { Text("请求超时（秒）") }, enabled = !running, modifier = Modifier.fillMaxWidth())
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("启用 Bearer API Key 认证")
      Switch(checked = authEnabled, onCheckedChange = { authEnabled = it }, enabled = !running)
    }
    if (authEnabled) {
      OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, enabled = !running, modifier = Modifier.fillMaxWidth())
      Button(onClick = { apiKey = viewModel.generateKey() }, enabled = !running) { Text("生成新的 API Key") }
    }

    Button(
      enabled = !running,
      onClick = {
        val parsedPort = port.toIntOrNull()
        val parsedTemperature = temperature.toDoubleOrNull()
        val parsedTokens = maxTokens.toIntOrNull()
        val parsedTopP = topP.toDoubleOrNull()
        val parsedTopK = topK.toIntOrNull()
        val parsedConcurrent = maxConcurrent.toIntOrNull()
        val parsedQueue = queueSize.toIntOrNull()
        val parsedTimeout = timeout.toLongOrNull()
        if (
          host.isNotBlank() && parsedPort in 1..65535 &&
          parsedTemperature != null && parsedTemperature in 0.0..2.0 &&
          parsedTokens != null && parsedTokens > 0 &&
          parsedTopP != null && parsedTopP in 0.0..1.0 &&
          parsedTopK != null && parsedTopK > 0 &&
          parsedConcurrent != null && parsedConcurrent in 1..16 &&
          parsedQueue != null && parsedQueue in 0..256 &&
          parsedTimeout != null && parsedTimeout >= 1 &&
          (!authEnabled || apiKey.isNotBlank())
        ) {
          viewModel.save(config.copy(
            host = host.trim(),
            port = parsedPort!!,
            authenticationEnabled = authEnabled,
            apiKey = apiKey.trim(),
            defaultTemperature = parsedTemperature,
            defaultMaxTokens = parsedTokens,
            defaultTopP = parsedTopP,
            defaultTopK = parsedTopK,
            maxConcurrent = parsedConcurrent,
            queueSize = parsedQueue,
            requestTimeoutMs = parsedTimeout * 1000,
          ))
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) { Text("保存配置") }

    Text("API 地址：http://手机局域网 IP:${config.port}/v1", style = MaterialTheme.typography.bodySmall)
    Button(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) { Text("查看 API 请求日志") }
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
  }
}
