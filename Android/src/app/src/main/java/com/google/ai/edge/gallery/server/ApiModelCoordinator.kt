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
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.EXTRA_CONTEXT_MAX_OUTPUT_TOKENS
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
 * This class intentionally does not hold a ModelManagerViewModel and never calls a CustomTask's
 * initialize/cleanup hooks: those hooks can reset the app-wide chat/agent session. API inference
 * uses LiteRT-LM's request-scoped ephemeral conversation so it does not reset the on-device chat.
 *
 * AICore inference is deliberately rejected for now. AICoreModelHelper currently stores one
 * inferenceJob per model and cancels it on every new request; calling it from the API could cancel
 * a user's foreground chat. It must not be enabled until request-level job isolation is added.
 */
@Singleton
class ApiModelCoordinator @Inject constructor(
  @ApplicationContext private val context: Context,
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
) : ApiModelGateway {

  private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private data class ModelEntry(val task: Task, val model: Model)

  private fun modelEntries(): List<ModelEntry> =
    customTasks.flatMap { customTask ->
      customTask.task.models.map { ModelEntry(customTask.task, it) }
    }.distinctBy { it.model.name }

  override fun listModels(): JSONArray {
    val result = JSONArray()
    modelEntries()
      .filter { it.model.isLlm && !it.model.isAiCore && isModelPresent(it.model) }
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

  override fun complete(request: JSONObject, onDelta: ((String) -> Unit)?): String {
    val modelName = request.optString("model").trim()
    require(modelName.isNotEmpty()) { "The 'model' field is required." }
    val entry = modelEntries().firstOrNull { it.model.name == modelName }
      ?: throw IllegalArgumentException("Unknown model: $modelName")
    val model = entry.model
    require(model.isLlm) { "Model '$modelName' is not a language model." }
    require(!model.isAiCore) {
      "AICore API inference is temporarily disabled to protect the Gallery foreground chat. " +
        "Request-scoped AICore inference isolation is not implemented yet."
    }
    require(isModelPresent(model)) { "Model '$modelName' is not downloaded." }

    val prompt = messagesToPrompt(request.optJSONArray("messages")
      ?: throw IllegalArgumentException("'messages' must be an array."))
    val maxTokens = request.optInt("max_tokens", 0).takeIf { it > 0 }
    val output = StringBuilder()
    val failure = AtomicReference<String?>(null)
    val completed = CountDownLatch(1)

    runBlocking(Dispatchers.IO) {
      val loadError = model.runtimeHelper.loadModel(
        context = context,
        model = model,
        taskId = entry.task.id,
        supportImage = false,
        supportAudio = false,
      )
      check(loadError == null) { loadError ?: "Unable to load model '$modelName'." }
    }

    val extraContext = buildMap {
      put("aicore_ephemeral_inference", "true")
      if (maxTokens != null) put(EXTRA_CONTEXT_MAX_OUTPUT_TOKENS, maxTokens.toString())
    }

    model.runtimeHelper.runInference(
      model = model,
      input = prompt,
      resultListener = { partial, done, _ ->
        if (partial.isNotEmpty()) {
          synchronized(output) { output.append(partial) }
          onDelta?.invoke(partial)
        }
        if (done) completed.countDown()
      },
      cleanUpListener = {},
      onError = { message ->
        failure.compareAndSet(null, message)
        completed.countDown()
      },
      coroutineScope = requestScope,
      extraContext = extraContext,
    )

    check(completed.await(10, TimeUnit.MINUTES)) { "Model inference timed out." }
    failure.get()?.let { throw IllegalStateException(it) }
    return synchronized(output) { output.toString() }
  }

  private fun isModelPresent(model: Model): Boolean =
    runCatching { File(model.getPath(context)).exists() }.getOrDefault(false)

  private fun messagesToPrompt(messages: JSONArray): String {
    require(messages.length() > 0) { "'messages' must contain at least one message." }
    val prompt = StringBuilder()
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
            if (part.optString("type") == "text") append(part.optString("text"))
          }
        }
        else -> throw IllegalArgumentException("messages[$index].content must be text.")
      }
      prompt.append(role.uppercase()).append(": ").append(text).append('\n')
    }
    prompt.append("ASSISTANT:")
    return prompt.toString()
  }
}
