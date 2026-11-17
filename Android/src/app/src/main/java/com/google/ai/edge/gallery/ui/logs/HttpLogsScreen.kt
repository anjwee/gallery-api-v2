package com.google.ai.edge.gallery.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.HttpTrafficLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpLogsScreen(onBack: () -> Unit) {
  val logs by HttpTrafficLogger.logs.collectAsState()
  val enabled by HttpTrafficLogger.isLoggingEnabled.collectAsState()
  Scaffold(topBar = {
    TopAppBar(title = { Text("API 请求日志") }, navigationIcon = {
      TextButton(onClick = onBack) { Text("返回") }
    }, actions = {
      Row {
        Text(if (enabled) "记录中" else "已暂停")
        Switch(checked = enabled, onCheckedChange = HttpTrafficLogger::setEnabled)
      }
      TextButton(onClick = HttpTrafficLogger::clear) { Text("清空") }
    })
  }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
      Text("共 ${logs.size} 条记录 · 最多保存 500 条", style = MaterialTheme.typography.bodySmall)
      if (logs.isEmpty()) {
        Text("暂无 API 请求记录。启动服务并从局域网发送请求后，记录会显示在这里。", modifier = Modifier.padding(top = 24.dp))
      } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          items(logs.reversed()) { entry ->
            Card(Modifier.fillMaxWidth()) {
              Column(Modifier.padding(12.dp)) {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.time))
                Text("$time  ${entry.method} ${entry.path}", style = MaterialTheme.typography.titleSmall)
                if (entry.status == 0) {
                  Text("处理中 · ${entry.detail}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                  Text("HTTP ${entry.status} · ${entry.detail}", style = MaterialTheme.typography.bodyMedium)
                }
              }
            }
          }
        }
      }
    }
  }
}
