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

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerUiState
import com.google.ai.edge.gallery.runtime.EXTRA_CONTEXT_MAX_OUTPUT_TOKENS
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges the LAN API to Gallery's existing model definitions and LiteRT-LM runtime.
 *
 * The active ModelManagerViewModel is attached by the settings screen when the user starts the
 * server. This lets the API use Gallery's live task list and download-state registry. API inference
 * still uses LiteRT-LM's request-scoped ephemeral conversation to isolate foreground chat state.
 *
 * AICore inference is deliberately rejected for now. AICoreModelHelper currently stores one
 * inferenceJob per model and cancels it on every new request; calling it from the API could cancel
 * a user's foreground chat. It must not be enabled until request-level job isolation is added.
 */
@Singleton
class ApiModelCoordinator @Inject constructor(
  @ApplicationContext private val context: Context,
) : ApiModelGateway {

  private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private data class ModelEntry(val task: Task, val model: Model)

  // Use the exact task/model registry and download state displayed by Gallery's Models screen.
  @Volatile private var modelManagerViewModel: ModelManagerViewModel? = null

  fun attachModelManager(viewModel: ModelManagerViewModel) {
    modelManagerViewModel = viewModel
  }

  private fun modelEntries(state: ModelManagerUiState): List<ModelEntry> =
    state.tasks.flatMap { task ->
      task.models.map { ModelEntry(task, it) }
    }.distinctBy { it.model.name }

  override fun listModels(): JSONArray {
    val result = JSONArray()
    val state = modelManagerViewModel?.uiState?.value ?: return result
    modelEntries(state)
      .filter { it.model.isLlm && !it.model.isAiCore && isModelPresent(it.model, state) }
      .forEach { entry ->
        result.put(
          JSONObject()
            .put("id", entry.model.name)
            .put("object", "model")
            .put("created", 0)
            .put("owned_by", "google-ai-edge-gallery")
            .put("name", entry.model.displayName.ifBlank { entry.model.name }),
        )
      }
    return result
  }

  @OptIn(ExperimentalApi::class)
  override fun complete(
    request: JSONObject,
    onDelta: ((String) -> Unit)?,
    onToolCalls: ((List<ApiToolCall>) -> Unit)?,
    onProgress: ((String) -> Unit)?,
  ): ApiCompletionResult {
    fun progress(message: String) {
      Log.i(TAG, "event=inference_progress detail=$message")
      onProgress?.invoke(message)
    }
    progress("正在匹配模型")
    val modelName = request.optString("model").trim()
    require(modelName.isNotEmpty()) { "The 'model' field is required." }
    val state = modelManagerViewModel?.uiState?.value
      ?: throw IllegalStateException("Model manager is not attached to the API server.")
    val entry = modelEntries(state).firstOrNull { entry ->
      val model = entry.model
      model.name.equals(modelName, ignoreCase = true) ||
        model.modelId.equals(modelName, ignoreCase = true) ||
        model.displayName.equals(modelName, ignoreCase = true) ||
        model.downloadInfo.downloadFileName.equals(modelName, ignoreCase = true)
    } ?: throw IllegalArgumentException("Unknown model: $modelName")
    val model = entry.model
    progress("模型已匹配：${model.displayName.ifBlank { model.name }}")
    require(model.isLlm) { "Model '$modelName' is not a language model." }
    require(!model.isAiCore) {
      "AICore API inference is temporarily disabled to protect the Gallery foreground chat. " +
        "Request-scoped AICore inference isolation is not implemented yet."
    }
    val downloadStatus = state.modelDownloadStatus[model.name]
    val downloadedOnDisk = modelManagerViewModel?.isModelDownloaded(model) == true
    Log.i(
      TAG,
      "event=model_download_status model=${model.name} status=${downloadStatus?.status} " +
        "downloaded_on_disk=$downloadedOnDisk",
    )
    require(isModelPresent(model, state)) { "Model '$modelName' is not downloaded." }

    val payload = parseRequest(request)
    val maxTokens = outputTokenLimit(request)
    val output = StringBuilder()
    val toolCalls = mutableListOf<ApiToolCall>()
    val failure = AtomicReference<String?>(null)
    val completed = CountDownLatch(1)

    progress("正在加载模型运行时")
    runBlocking(Dispatchers.IO) {
      val loadError = model.runtimeHelper.loadModel(
        context = context,
        model = model,
        taskId = entry.task.id,
        supportImage = payload.hasImage,
        supportAudio = payload.hasAudio,
        onProgress = { detail -> progress("模型加载：$detail") },
      )
      check(loadError == null) { loadError ?: "Unable to load model '$modelName'." }
    }

    val instance = model.instance as? LlmModelInstance
      ?: throw IllegalStateException("The selected model is not backed by the LiteRT-LM runtime.")
    progress("模型加载完成，正在初始化手动工具调用会话")
    // Manual mode is essential: LiteRT-LM returns tool calls to this API without executing them.
    // Hermes on the remote Linux host remains the only process that executes tools.
    ExperimentalFlags.enableConversationConstrainedDecoding = payload.tools.isNotEmpty()
    val conversation = try {
      instance.engine.createConversation(
        ConversationConfig(
          tools = payload.tools,
          initialMessages = payload.initialMessages,
          automaticToolCalling = false,
        )
      )
    } finally {
      ExperimentalFlags.enableConversationConstrainedDecoding = false
    }

    instance.activeEphemeralConversations.add(conversation)
    val extraContext = buildMap {
      put("aicore_ephemeral_inference", "true")
      if (maxTokens != null) put(EXTRA_CONTEXT_MAX_OUTPUT_TOKENS, maxTokens.toString())
    }

    try {
      progress("会话初始化完成，开始推理")
      val inferenceStartedAt = System.nanoTime()
      val callbackCount = AtomicInteger(0)
      conversation.sendMessageAsync(
        payload.finalMessage,
        object : MessageCallback {
          override fun onMessage(message: Message) {
            val count = callbackCount.incrementAndGet()
            Log.i(TAG, "event=inference_message callback=$count text_chars=${message.toString().length} tool_calls=${message.toolCalls.size} elapsed_ms=${(System.nanoTime() - inferenceStartedAt) / 1_000_000}")
            val text = message.toString()
            if (text.isNotEmpty()) {
              synchronized(output) { output.append(text) }
              onDelta?.invoke(text)
            }
            if (message.toolCalls.isNotEmpty()) {
              synchronized(toolCalls) {
                toolCalls.clear()
                message.toolCalls.forEach { call ->
                  toolCalls.add(
                    ApiToolCall(
                      id = "call_${UUID.randomUUID()}",
                      name = call.name,
                      arguments = JSONObject(call.arguments).toString(),
                    )
                  )
                }
              }
            }
          }

          override fun onDone() {
            Log.i(TAG, "event=inference_done callbacks=${callbackCount.get()} elapsed_ms=${(System.nanoTime() - inferenceStartedAt) / 1_000_000}")
            progress("模型推理完成")
            completed.countDown()
          }

          override fun onError(throwable: Throwable) {
            Log.e(TAG, "event=inference_error callbacks=${callbackCount.get()} elapsed_ms=${(System.nanoTime() - inferenceStartedAt) / 1_000_000} message=${throwable.message}", throwable)
            failure.compareAndSet(null, throwable.message ?: "Unknown inference error")
            completed.countDown()
          }
        },
        extraContext,
        maxOutputToken = maxTokens,
      )
      progress("等待模型生成结果")
      check(completed.await(10, TimeUnit.MINUTES)) { "Model inference timed out; callbacks_received=${callbackCount.get()}." }
      failure.get()?.let { throw IllegalStateException(it) }
    } finally {
      instance.activeEphemeralConversations.remove(conversation)
      runCatching { conversation.close() }
    }

    val finalToolCalls = synchronized(toolCalls) { toolCalls.toList() }
    if (finalToolCalls.isNotEmpty()) {
      progress("模型已生成 ${finalToolCalls.size} 个工具调用，等待 Hermes 执行")
      onToolCalls?.invoke(finalToolCalls)
      return ApiCompletionResult(content = null, toolCalls = finalToolCalls)
    }
    return ApiCompletionResult(content = synchronized(output) { output.toString() })
  }

  companion object {
    private const val TAG = "ApiModelCoordinator"
  }

  private fun isModelPresent(model: Model, state: ModelManagerUiState): Boolean {
    // The UI status map can lag behind imported-model file operations or a state refresh.
    // Confirm the actual file as a fallback so a valid local model remains usable by the API.
    return state.modelDownloadStatus[model.name]?.status == ModelDownloadStatusType.SUCCEEDED ||
      modelManagerViewModel?.isModelDownloaded(model) == true
  }

  private data class PromptPayload(
    val initialMessages: List<Message>,
    val finalMessage: Message,
    val hasImage: Boolean,
    val hasAudio: Boolean,
    val tools: List<com.google.ai.edge.litertlm.ToolProvider>,
  )

  private fun outputTokenLimit(request: JSONObject): Int? {
    val field = when {
      request.has("max_completion_tokens") && !request.isNull("max_completion_tokens") -> "max_completion_tokens"
      request.has("max_tokens") && !request.isNull("max_tokens") -> "max_tokens"
      else -> return null
    }
    val value = request.opt(field)
    require(value is Number) { "The $field field must be a positive integer." }
    val number = value as Number
    val limit = number.toInt()
    require(limit > 0 && limit.toDouble() == number.toDouble()) { "The $field field must be a positive integer." }
    return limit
  }
  private fun parseRequest(request: JSONObject): PromptPayload {
    val messages = request.optJSONArray("messages")
      ?: throw IllegalArgumentException("'messages' must be an array.")
    require(messages.length() > 0) { "'messages' must contain at least one message." }

    val idToToolName = mutableMapOf<String, String>()
    val parsedMessages = mutableListOf<Message>()
    var hasImage = false
    var hasAudio = false

    for (index in 0 until messages.length()) {
      val message = messages.optJSONObject(index)
        ?: throw IllegalArgumentException("messages[$index] must be an object.")
      val role = message.optString("role").lowercase()
      require(role in setOf("system", "developer", "user", "assistant", "tool")) {
        "Unsupported message role '$role'."
      }

      val contentParts = parseContent(message.opt("content"))
      hasImage = hasImage || contentParts.any { it is Content.ImageBytes }
      hasAudio = hasAudio || contentParts.any { it is Content.AudioBytes }
      val contents = Contents.of(contentParts)
      val text = contentParts.filterIsInstance<Content.Text>().joinToString("") { it.text }

      val parsed = when (role) {
        "system", "developer" -> Message.system(contents)
        "user" -> Message.user(contents)
        "assistant" -> {
          val callsJson = message.optJSONArray("tool_calls")
          val calls = mutableListOf<ToolCall>()
          if (callsJson != null) {
            for (callIndex in 0 until callsJson.length()) {
              val call = callsJson.optJSONObject(callIndex) ?: continue
              val function = call.optJSONObject("function")
                ?: throw IllegalArgumentException("messages[$index].tool_calls[$callIndex].function is required.")
              val name = function.optString("name").trim()
              require(name.isNotEmpty()) { "Tool call function name is required." }
              val argumentsValue = function.opt("arguments")
              val arguments = when (argumentsValue) {
                is JSONObject -> argumentsValue
                is String -> JSONObject(argumentsValue)
                else -> JSONObject()
              }
              val callId = call.optString("id").ifBlank { "history_call_${UUID.randomUUID()}" }
              idToToolName[callId] = name
              calls.add(ToolCall(name, jsonObjectToMap(arguments)))
            }
          }
          Message.model(contents, calls)
        }
        "tool" -> {
          val callId = message.optString("tool_call_id")
          val name = message.optString("name").ifBlank { idToToolName[callId].orEmpty() }
          require(name.isNotBlank()) {
            "Tool message '$index' must include a name or a matching tool_call_id."
          }
          val response = message.opt("content")
          Message.tool(Contents.of(Content.ToolResponse(name, response?.toString() ?: "")))
        }
        else -> throw IllegalArgumentException("Unsupported message role '$role'.")
      }
      parsedMessages.add(parsed)
    }

    val toolDefinitions = parseTools(request)
    val choice = request.opt("tool_choice")
    val effectiveTools = when (choice) {
      null, JSONObject.NULL -> toolDefinitions.values.toList()
      is String -> when (choice.lowercase()) {
        "none" -> emptyList()
        "auto" -> toolDefinitions.values.toList()
        "required" -> {
          require(toolDefinitions.isNotEmpty()) {
            "tool_choice=required requires at least one function tool."
          }
          // The runtime can constrain tool-call output, but strict forced-call semantics
          // still depend on LiteRT-LM runtime support.
          toolDefinitions.values.toList()
        }
        else -> throw IllegalArgumentException("Unsupported tool_choice: $choice")
      }
      is JSONObject -> {
        val function = choice.optJSONObject("function")
          ?: throw IllegalArgumentException("tool_choice.function is required for function selection.")
        val selectedName = function.optString("name").trim()
        require(selectedName.isNotEmpty()) { "tool_choice.function.name is required." }
        val selectedTool = toolDefinitions[selectedName]
          ?: throw IllegalArgumentException("tool_choice references unknown function: $selectedName")
        listOf(selectedTool)
      }
      else -> throw IllegalArgumentException("tool_choice must be a string or function selector object.")
    }

    return PromptPayload(
      initialMessages = parsedMessages.dropLast(1),
      finalMessage = parsedMessages.last(),
      hasImage = hasImage,
      hasAudio = hasAudio,
      tools = effectiveTools,
    )
  }

  private fun parseContent(content: Any?): List<Content> {
    return when (content) {
      null, JSONObject.NULL -> emptyList()
      is String -> listOf(Content.Text(content))
      is JSONArray -> buildList {
        for (index in 0 until content.length()) {
          val part = content.optJSONObject(index) ?: continue
          when (part.optString("type")) {
            "text", "input_text" -> add(Content.Text(part.optString("text")))
            "image_url" -> {
              val imageUrl = part.opt("image_url")
              val dataUrl = if (imageUrl is JSONObject) imageUrl.optString("url") else imageUrl?.toString().orEmpty()
              require(dataUrl.startsWith("data:image/") && dataUrl.contains(";base64,")) {
                "Only base64 data URLs are supported for image_url. Remote image URLs are not supported."
              }
              val bytes = decodeBase64(dataUrl.substringAfter(";base64,"), "image_url")
              add(Content.ImageBytes(bytes))
            }
            "input_audio" -> {
              val audio = part.optJSONObject("input_audio")
                ?: throw IllegalArgumentException("input_audio must be an object.")
              val encoded = audio.optString("data")
              require(encoded.isNotBlank()) { "input_audio.data is required." }
              add(Content.AudioBytes(decodeBase64(encoded.substringAfter("base64,", encoded), "input_audio.data")))
            }
          }
        }
      }
      else -> throw IllegalArgumentException("Message content must be text or a content array.")
    }
  }

  private fun parseTools(request: JSONObject): Map<String, com.google.ai.edge.litertlm.ToolProvider> {
    val tools = request.optJSONArray("tools") ?: return emptyMap()
    val result = linkedMapOf<String, com.google.ai.edge.litertlm.ToolProvider>()
    for (index in 0 until tools.length()) {
      val item = tools.optJSONObject(index)
        ?: throw IllegalArgumentException("tools[$index] must be an object.")
      require(item.optString("type", "function") == "function") {
        "Only function tools are supported."
      }
      val function = item.optJSONObject("function")
        ?: throw IllegalArgumentException("tools[$index].function is required.")
      val name = function.optString("name").trim()
      require(name.isNotEmpty()) { "tools[$index].function.name is required." }
      val description = function.optString("description")
      val parameters = function.optJSONObject("parameters")
        ?: JSONObject().put("type", "object").put("properties", JSONObject())
      val schema = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("parameters", parameters)
        .toString()
      require(!result.containsKey(name)) { "Duplicate tool name: $name" }
      result[name] = tool(object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = schema

        // Manual mode must never invoke this method. Returning a hard error is safer than
        // accidentally running a tool inside the Android process if the runtime changes behavior.
        override fun execute(paramsJsonString: String): String =
          throw UnsupportedOperationException("Tool execution is disabled on the Android API. Execute '$name' on the remote agent.")
      })
    }
    return result
  }

  private fun jsonObjectToMap(value: JSONObject): Map<String, Any?> =
    value.keys().asSequence().associateWith { key -> jsonValue(value.opt(key)) }

  private fun jsonValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> jsonObjectToMap(value)
    is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
    else -> value
  }

  private fun decodeBase64(value: String, field: String): ByteArray =
    try {
      Base64.decode(value, Base64.DEFAULT)
    } catch (exception: IllegalArgumentException) {
      throw IllegalArgumentException("$field must contain valid base64 data.")
    }
}
