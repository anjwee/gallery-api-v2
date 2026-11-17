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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerUiState
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.EXTRA_CONTEXT_MAX_OUTPUT_TOKENS
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

  override fun complete(
    request: JSONObject,
    onDelta: ((String) -> Unit)?,
    onProgress: ((String) -> Unit)?,
  ): String {
    fun progress(message: String) {
      Log.i(TAG, "event=inference_progress detail=$message")
      onProgress?.invoke(message)
    }
    progress("正在匹配模型")
    val modelName = request.optString("model").trim()
    require(modelName.isNotEmpty()) { "The 'model' field is required." }
    // Read the registry and download status once so a concurrent UI-state refresh cannot
    // make model lookup and the downloaded check observe different snapshots.
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

    val prompt = messagesToPrompt(request.optJSONArray("messages")
      ?: throw IllegalArgumentException("'messages' must be an array."))
    val maxTokens = request.optInt("max_tokens", 0).takeIf { it > 0 }
    val output = StringBuilder()
    val failure = AtomicReference<String?>(null)
    val completed = CountDownLatch(1)

    progress("正在加载模型运行时")
    runBlocking(Dispatchers.IO) {
      val loadError = model.runtimeHelper.loadModel(
        context = context,
        model = model,
        taskId = entry.task.id,
        supportImage = prompt.images.isNotEmpty(),
        supportAudio = prompt.audioClips.isNotEmpty(),
        onProgress = { detail -> progress("模型加载：$detail") },
      )
      check(loadError == null) { loadError ?: "Unable to load model '$modelName'." }
    }

    progress("模型加载完成，正在初始化对话会话")
    model.runtimeHelper.resetConversationOrThrow(
      model = model,
      supportImage = prompt.images.isNotEmpty(),
      supportAudio = prompt.audioClips.isNotEmpty(),
    )
    progress("会话初始化完成，开始推理")

    val extraContext = buildMap {
      put("aicore_ephemeral_inference", "true")
      if (maxTokens != null) put(EXTRA_CONTEXT_MAX_OUTPUT_TOKENS, maxTokens.toString())
    }

    model.runtimeHelper.runInference(
      model = model,
      input = prompt.text,
      resultListener = { partial, done, _ ->
        if (partial.isNotEmpty()) {
          progress("模型正在生成回答")
          synchronized(output) { output.append(partial) }
          onDelta?.invoke(partial)
        }
        if (done) {
          progress("模型推理完成")
          completed.countDown()
        }
      },
      cleanUpListener = {},
      onError = { message ->
        Log.e(TAG, "event=inference_error message=$message")
        progress("推理失败：$message")
        failure.compareAndSet(null, message)
        completed.countDown()
      },
      images = prompt.images,
      audioClips = prompt.audioClips,
      coroutineScope = requestScope,
      extraContext = extraContext,
    )

    progress("等待模型生成结果")
    check(completed.await(10, TimeUnit.MINUTES)) { "Model inference timed out." }
    failure.get()?.let { throw IllegalStateException(it) }
    return synchronized(output) { output.toString() }
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
    val text: String,
    val images: List<Bitmap>,
    val audioClips: List<ByteArray>,
  )

  private fun messagesToPrompt(messages: JSONArray): PromptPayload {
    require(messages.length() > 0) { "'messages' must contain at least one message." }
    val prompt = StringBuilder()
    val images = mutableListOf<Bitmap>()
    val audioClips = mutableListOf<ByteArray>()
    for (index in 0 until messages.length()) {
      val message = messages.optJSONObject(index)
        ?: throw IllegalArgumentException("messages[$index] must be an object.")
      val role = message.optString("role").lowercase()
      require(role in setOf("system", "user", "assistant")) {
        "Unsupported message role '$role'. Tool messages are not supported yet."
      }
      val content = message.opt("content")
      val text = when (content) {
        is String -> content
        is JSONArray -> buildString {
          for (partIndex in 0 until content.length()) {
            val part = content.optJSONObject(partIndex) ?: continue
            when (part.optString("type")) {
              "text", "input_text" -> append(part.optString("text"))
              "image_url" -> {
                val imageUrl = part.opt("image_url")
                val dataUrl = if (imageUrl is JSONObject) imageUrl.optString("url") else imageUrl?.toString().orEmpty()
                require(dataUrl.startsWith("data:image/") && dataUrl.contains(";base64,")) {
                  "Only base64 data URLs are supported for image_url. Remote image URLs are not supported."
                }
                val encoded = dataUrl.substringAfter(";base64,")
                val bytes = decodeBase64(encoded, "image_url")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                  ?: throw IllegalArgumentException("Unable to decode image_url image data.")
                images.add(bitmap)
              }
              "input_audio" -> {
                val audio = part.optJSONObject("input_audio")
                  ?: throw IllegalArgumentException("input_audio must be an object.")
                val encoded = audio.optString("data")
                require(encoded.isNotBlank()) { "input_audio.data is required." }
                audioClips.add(decodeBase64(encoded.substringAfter("base64,", encoded), "input_audio.data"))
              }
            }
          }
        }
        else -> throw IllegalArgumentException("messages[$index].content must be text or a content array.")
      }
      prompt.append(role.uppercase()).append(": ").append(text).append('\n')
    }
    prompt.append("ASSISTANT:")
    return PromptPayload(prompt.toString(), images, audioClips)
  }

  private fun decodeBase64(value: String, field: String): ByteArray =
    try {
      Base64.decode(value, Base64.DEFAULT)
    } catch (exception: IllegalArgumentException) {
      throw IllegalArgumentException("$field must contain valid base64 data.")
    }
}
