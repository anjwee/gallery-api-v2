/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.server

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Network-facing model contract. The implementation must delegate to Gallery's existing
 * model manager/runtime; this server deliberately does not create or own a second model runtime.
 */
data class ApiToolCall(
  val id: String,
  val name: String,
  val arguments: String,
)

data class ApiCompletionResult(
  val content: String?,
  val toolCalls: List<ApiToolCall> = emptyList(),
) {
  val finishReason: String
    get() = if (toolCalls.isNotEmpty()) "tool_calls" else "stop"
}

/**
 * Network-facing model contract. The gateway returns tool calls as data only. Tool execution
 * belongs to the remote agent (Hermes), never to this Android API process.
 */
interface ApiModelGateway {
  fun listModels(): JSONArray

  fun complete(
    request: JSONObject,
    onDelta: ((String) -> Unit)? = null,
    onToolCalls: ((List<ApiToolCall>) -> Unit)? = null,
    onProgress: ((String) -> Unit)? = null,
  ): ApiCompletionResult
}

/** Small immutable snapshot of the HTTP server configuration. */
data class ApiHttpServerConfig(
  val bindAddress: String = "0.0.0.0",
  val port: Int = 8080,
  val apiKey: String = "",
  val authenticationEnabled: Boolean = true,
  val maxRequestBytes: Int = 1024 * 1024,
  val socketReadTimeoutMs: Int = 30_000,
) {
  init {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    require(maxRequestBytes in 1..8 * 1024 * 1024) { "Invalid request size limit" }
    require(socketReadTimeoutMs > 0) { "Socket timeout must be positive" }
  }
}

/**
 * Minimal HTTP/1.1 transport for the LAN OpenAI-compatible API.
 *
 * It supports /health, /v1/models, /v1/chat/completions, bearer-token authentication and
 * OpenAI-style SSE. A fresh executor is created for every start so stop/start is supported.
 */
class ApiHttpServer(
  private val configProvider: () -> ApiHttpServerConfig,
  private val gateway: ApiModelGateway,
  private val onStateChanged: (running: Boolean, detail: String?) -> Unit = { _, _ -> },
  private val onRequestReceived: (method: String, path: String, detail: String) -> Unit =
    { _, _, _ -> },
  private val onRequestProgress: (method: String, path: String, detail: String) -> Unit =
    { _, _, _ -> },
  private val onRequest: (method: String, path: String, status: Int, elapsedMs: Long) -> Unit =
    { _, _, _, _ -> },
) {
  private val running = AtomicBoolean(false)
  private val activeConnections = AtomicInteger(0)
  @Volatile private var startedAtMillis: Long = 0L
  @Volatile private var serverSocket: ServerSocket? = null
  @Volatile private var acceptThread: Thread? = null
  @Volatile private var workers: ExecutorService? = null

  @Synchronized
  fun start() {
    if (running.get()) return
    val config = configProvider()
    val socket = ServerSocket()
    socket.reuseAddress = true
    socket.bind(
      java.net.InetSocketAddress(InetAddress.getByName(config.bindAddress), config.port),
      50,
    )
    serverSocket = socket
    startedAtMillis = System.currentTimeMillis()
    activeConnections.set(0)
    workers = Executors.newCachedThreadPool()
    running.set(true)
    onStateChanged(true, "Listening on ${config.bindAddress}:${config.port}")
    acceptThread = Thread({
      while (running.get()) {
        try {
          val client = socket.accept()
          try {
            client.soTimeout = configProvider().socketReadTimeoutMs
            workers?.execute {
              activeConnections.incrementAndGet()
              try {
                handleClient(client)
              } finally {
                activeConnections.decrementAndGet()
              }
            } ?: client.close()
          } catch (exception: Exception) {
            runCatching { client.close() }
          }
        } catch (error: SocketException) {
          if (running.get()) stopWithError(error)
        } catch (error: Exception) {
          if (running.get()) stopWithError(error)
        }
      }
    }, "Gallery-Local-API-Accept").apply {
      isDaemon = true
      start()
    }
  }

  @Synchronized
  fun stop() {
    if (!running.getAndSet(false)) return
    runCatching { serverSocket?.close() }
    serverSocket = null
    workers?.shutdownNow()
    workers = null
    acceptThread = null
    onStateChanged(false, null)
  }

  fun isRunning(): Boolean = running.get()

  fun getUptimeMillis(): Long =
    if (running.get()) (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L) else 0L

  fun getCurrentConnections(): Int = activeConnections.get()

  private fun stopWithError(error: Exception) {
    stop()
    onStateChanged(false, error.message ?: error.javaClass.simpleName)
  }

  private fun handleClient(socket: Socket) {
    socket.use { client ->
      val startedAt = System.nanoTime()
      var method = "?"
      var path = "?"
      var status = 500
      try {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val requestLine = readAsciiLine(input, 8192) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 3) {
          writeJson(output, 400, error("bad_request", "Malformed request line"))
          status = 400
          return
        }
        method = parts[0].uppercase()
        path = parts[1].substringBefore('?')
        val headers = LinkedHashMap<String, String>()
        while (true) {
          val line = readAsciiLine(input, 16 * 1024) ?: break
          if (line.isEmpty()) break
          val colon = line.indexOf(':')
          if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] =
            line.substring(colon + 1).trim()
        }

        if (!isAuthorized(headers, configProvider())) {
          status = 401
          writeJson(output, status, error("unauthorized", "Missing or invalid API key"))
          return
        }

        when {
          method == "GET" && path == "/health" -> {
            onRequestReceived(method, path, "请求已收到，正在处理")
            status = 200
            writeJson(output, status, JSONObject().put("status", "ok"))
          }
          method == "GET" && path == "/v1/models" -> {
            onRequestReceived(method, path, "请求已收到，正在读取模型列表")
            status = 200
            val data = gateway.listModels()
            writeJson(output, status, JSONObject().put("object", "list").put("data", data))
          }
          method == "POST" && path == "/v1/chat/completions" -> {
            val length = headers["content-length"]?.toIntOrNull()
            if (length == null || length < 0 || length > configProvider().maxRequestBytes) {
              status = if (length != null && length > configProvider().maxRequestBytes) 413 else 400
              writeJson(output, status, error("invalid_request_error", "Invalid Content-Length"))
              return
            }
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
              val count = input.read(body, offset, length - offset)
              if (count < 0) throw IllegalArgumentException("Unexpected end of request body")
              offset += count
            }
            val request = try {
              JSONObject(String(body, Charsets.UTF_8))
            } catch (_: JSONException) {
              status = 400
              writeJson(output, status, error("invalid_request_error", "Request body must be JSON"))
              return
            }
            onRequestReceived(method, path, "stream=${request.optBoolean(\"stream\", false)} messages=${request.optJSONArray(\"messages\")?.length() ?: 0} tools=${request.optJSONArray(\"tools\")?.length() ?: 0} | ${requestPreview(request)}")
            if (request.optBoolean("stream", false)) {
              status = 200
              writeStreaming(output, request)
            } else {
              val answer = gateway.complete(request, onProgress = { detail ->
                onRequestProgress(method, path, detail)
              })
              status = 200
              writeJson(output, status, completionResponse(request, answer))
            }
          }
          else -> {
            status = 404
            writeJson(output, status, error("not_found", "Endpoint not found"))
          }
        }
      } catch (exception: IllegalArgumentException) {
        status = 400
        runCatching {
          writeJson(
            BufferedOutputStream(client.getOutputStream()),
            status,
            error("invalid_request_error", exception.message ?: "Invalid request"),
          )
        }
      } catch (exception: Exception) {
        status = 500
        runCatching {
          writeJson(
            BufferedOutputStream(client.getOutputStream()),
            status,
            error("server_error", exception.message ?: "Internal server error"),
          )
        }
      } finally {
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        runCatching { onRequest(method, path, status, elapsed) }
      }
    }
  }

  private fun writeStreaming(output: BufferedOutputStream, request: JSONObject) {
    val id = "chatcmpl-${UUID.randomUUID()}"
    val created = System.currentTimeMillis() / 1000
    val model = request.optString("model", "local-model")
    output.write(
      ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
        "Cache-Control: no-cache\r\nConnection: close\r\nX-Accel-Buffering: no\r\n\r\n")
        .toByteArray(Charsets.US_ASCII),
    )
    output.flush()

    // Keep quiet SSE streams alive while local inference generates its first token.
    // This prevents clients/proxies from timing out and retrying the same expensive request.
    val finished = AtomicBoolean(false)
    val heartbeat = Thread({
      while (!finished.get()) {
        try {
          Thread.sleep(10_000)
          if (!finished.get()) synchronized(output) {
            output.write(": keep-alive\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
          }
        } catch (_: InterruptedException) {
          break
        } catch (_: Exception) {
          break
        }
      }
    }, "Gallery-API-SSE-Heartbeat").apply { isDaemon = true; start() }

    try {
      val result = gateway.complete(request, onDelta = { delta ->
        if (delta.isNotEmpty()) {
          val chunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
            .put("created", created).put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("index", 0)
              .put("delta", JSONObject().put("content", delta))
              .put("finish_reason", JSONObject.NULL)))
          writeSse(output, chunk)
        }
      }, onToolCalls = { calls ->
        calls.forEachIndexed { index, call ->
          val delta = JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("index", index).put("id", call.id).put("type", "function")
            .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))))
          val chunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
            .put("created", created).put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("index", 0)
              .put("delta", delta).put("finish_reason", JSONObject.NULL)))
          writeSse(output, chunk)
        }
      }, onProgress = { detail -> onRequestProgress("POST", "/v1/chat/completions", detail) })

      val finalChunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
        .put("created", created).put("model", model)
        .put("choices", JSONArray().put(JSONObject().put("index", 0)
          .put("delta", JSONObject()).put("finish_reason", result.finishReason)))
      writeSse(output, finalChunk)
      synchronized(output) {
        output.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
      }
    } catch (exception: Exception) {
      // After SSE headers are sent, errors must remain SSE events, not a second HTTP response.
      runCatching {
        val payload = JSONObject().put("error", JSONObject().put("type", "server_error")
          .put("message", exception.message ?: "Inference failed"))
        synchronized(output) {
          output.write(("data: ${payload}\n\ndata: [DONE]\n\n").toByteArray(Charsets.UTF_8))
          output.flush()
        }
      }
    } finally {
      finished.set(true)
      heartbeat.interrupt()
      runCatching { heartbeat.join(1_000) }
    }
  }

  private fun writeSse(output: BufferedOutputStream, payload: JSONObject) {
    synchronized(output) {
      output.write(("data: ${payload}\n\n").toByteArray(Charsets.UTF_8))
      output.flush()
    }
  }

  /** A short, privacy-conscious preview for the live request log; never logs headers or API keys. */
  private fun requestPreview(request: JSONObject): String {
    val messages = request.optJSONArray("messages") ?: return "聊天请求已收到"
    for (index in messages.length() - 1 downTo 0) {
      val message = messages.optJSONObject(index) ?: continue
      if (message.optString("role") != "user") continue
      val content = message.opt("content")
      val text = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { partIndex ->
          val part = content.optJSONObject(partIndex) ?: return@mapNotNull null
          if (part.optString("type") == "text") part.optString("text") else null
        }.joinToString(" ")
        else -> ""
      }.replace(Regex("\\s+"), " ").trim().take(120)
      return if (text.isBlank()) "已收到用户消息" else "用户输入：$text"
    }
    return "聊天请求已收到"
  }

  private fun completionResponse(request: JSONObject, result: ApiCompletionResult): JSONObject {
    val message = JSONObject().put("role", "assistant")
    if (result.toolCalls.isNotEmpty()) {
      message.put("content", JSONObject.NULL)
      message.put("tool_calls", JSONArray().apply {
        result.toolCalls.forEach { call ->
          put(JSONObject()
            .put("id", call.id)
            .put("type", "function")
            .put("function", JSONObject()
              .put("name", call.name)
              .put("arguments", call.arguments)))
        }
      })
    } else {
      message.put("content", result.content ?: "")
    }
    val choice = JSONObject()
      .put("index", 0)
      .put("message", message)
      .put("finish_reason", result.finishReason)
    return JSONObject()
      .put("id", "chatcmpl-${UUID.randomUUID()}")
      .put("object", "chat.completion")
      .put("created", System.currentTimeMillis() / 1000)
      .put("model", request.optString("model", "local-model"))
      .put("choices", JSONArray().put(choice))
  }

  private fun isAuthorized(
    headers: Map<String, String>,
    config: ApiHttpServerConfig,
  ): Boolean {
    if (!config.authenticationEnabled) return true
    if (config.apiKey.isBlank()) return false
    val supplied = headers["authorization"] ?: return false
    val expected = "Bearer ${config.apiKey}"
    return MessageDigest.isEqual(
      supplied.toByteArray(Charsets.UTF_8),
      expected.toByteArray(Charsets.UTF_8),
    )
  }

  private fun writeJson(output: BufferedOutputStream, status: Int, body: JSONObject) {
    val bytes = body.toString().toByteArray(Charsets.UTF_8)
    val reason = when (status) {
      200 -> "OK"
      400 -> "Bad Request"
      401 -> "Unauthorized"
      404 -> "Not Found"
      413 -> "Payload Too Large"
      else -> "Internal Server Error"
    }
    val headers = "HTTP/1.1 $status $reason\r\n" +
      "Content-Type: application/json; charset=utf-8\r\n" +
      "Content-Length: ${bytes.size}\r\n" +
      "Connection: close\r\n\r\n"
    output.write(headers.toByteArray(Charsets.US_ASCII))
    output.write(bytes)
    output.flush()
  }

  private fun error(type: String, message: String): JSONObject =
    JSONObject().put("error", JSONObject().put("type", type).put("message", message))

  private fun readAsciiLine(input: BufferedInputStream, maxBytes: Int): String? {
    val bytes = java.io.ByteArrayOutputStream()
    while (bytes.size() <= maxBytes) {
      val value = input.read()
      if (value < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
      if (value == '\n'.code) break
      if (value != '\r'.code) bytes.write(value)
    }
    if (bytes.size() > maxBytes) throw IllegalArgumentException("HTTP header line too long")
    return bytes.toString("US-ASCII")
  }
}
