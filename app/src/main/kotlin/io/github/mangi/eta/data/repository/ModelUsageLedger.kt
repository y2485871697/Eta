package io.github.mangi.eta.data.repository

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal data class ModelUsageSnapshot(
    val providers: List<ModelUsageProviderUi> = emptyList(),
) {
    val totalInputTokens: Long get() = providers.sumOf { it.inputTokens }
    val totalOutputTokens: Long get() = providers.sumOf { it.outputTokens }
    val billedCredits: Double get() = providers.sumOf { it.billedCredits ?: 0.0 }

    fun filtered(startMillis: Long?, endMillis: Long?): ModelUsageSnapshot {
        if (startMillis == null && endMillis == null) return this
        return ModelUsageSnapshot(
            providers = providers.mapNotNull { provider ->
                val models = provider.models.mapNotNull { it.filtered(startMillis, endMillis) }
                if (models.isEmpty()) null else provider.copy(models = models)
            },
        )
    }
}

internal data class ModelUsageProviderUi(
    val id: String,
    val name: String,
    val models: List<ModelUsageModelUi>,
) {
    val inputTokens: Long get() = models.sumOf { it.inputTokens }
    val outputTokens: Long get() = models.sumOf { it.outputTokens }
    val billedCredits: Double? get() {
        val values = models.mapNotNull { it.billedCredits }
        if (values.isEmpty()) return null
        return values.sum()
    }
}

internal data class ModelUsageEvent(
    val atMillis: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val conversationId: String? = null,
)

internal data class ModelUsageModelUi(
    val id: String,
    val displayName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val conversationCount: Int,
    val activeDays: Int,
    val events: List<ModelUsageEvent> = emptyList(),
    val billedCredits: Double? = null,
) {
    val dailyAverageTokens: Long
        get() = if (activeDays <= 0) 0L else inputTokens / activeDays
    val conversationAverageTokens: Long
        get() = if (conversationCount <= 0) 0L else inputTokens / conversationCount

    fun filtered(startMillis: Long?, endMillis: Long?): ModelUsageModelUi? {
        if (startMillis == null && endMillis == null) return this
        val matched = events.filter { event ->
            (startMillis == null || event.atMillis >= startMillis) &&
                (endMillis == null || event.atMillis <= endMillis)
        }
        if (matched.isEmpty()) return null
        val conversations = matched.mapNotNull { it.conversationId }.toSet()
        val days = matched.map { eventDay(it.atMillis) }.toSet()
        val filteredInput = matched.sumOf { it.inputTokens }
        val conversion = billedCredits?.takeIf { inputTokens > 0 }?.div(inputTokens.toDouble())
        return copy(
            inputTokens = filteredInput,
            outputTokens = matched.sumOf { it.outputTokens },
            conversationCount = conversations.size,
            activeDays = days.size,
            events = matched,
            billedCredits = conversion?.times(filteredInput),
        )
    }
}

internal data class ModelUsageDelta(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelDisplayName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val conversationId: String? = null,
    val atMillis: Long = System.currentTimeMillis(),
    val day: LocalDate = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
)

internal fun decodeModelUsageSnapshot(raw: String?): ModelUsageSnapshot {
    if (raw.isNullOrBlank()) return ModelUsageSnapshot()
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return ModelUsageSnapshot()
    val providersJson = root.optJSONObject("providers") ?: return ModelUsageSnapshot()
    val providers = buildList {
        providersJson.keys().forEach { providerId ->
            val provider = providersJson.optJSONObject(providerId) ?: return@forEach
            val modelsJson = provider.optJSONObject("models") ?: JSONObject()
            val models = buildList {
                modelsJson.keys().forEach { modelId ->
                    val model = modelsJson.optJSONObject(modelId) ?: return@forEach
                    val events = decodeEvents(model.optJSONArray("events"))
                    val conversations = stringSet(model.optJSONArray("conversations"))
                    val days = stringSet(model.optJSONArray("days"))
                    val input = if (events.isNotEmpty()) {
                        events.sumOf { it.inputTokens }
                    } else {
                        model.optLong("inputTokens")
                    }
                    val output = if (events.isNotEmpty()) {
                        events.sumOf { it.outputTokens }
                    } else {
                        model.optLong("outputTokens")
                    }
                    add(
                        ModelUsageModelUi(
                            id = modelId,
                            displayName = model.optString("displayName").ifBlank { modelId },
                            inputTokens = input,
                            outputTokens = output,
                            conversationCount = if (events.isNotEmpty()) {
                                events.mapNotNull { it.conversationId }.toSet().size
                            } else {
                                conversations.size
                            },
                            activeDays = if (events.isNotEmpty()) {
                                events.map { eventDay(it.atMillis) }.toSet().size
                            } else {
                                days.size
                            },
                            events = events,
                        ),
                    )
                }
            }.sortedWith(
                compareByDescending<ModelUsageModelUi> { it.inputTokens }
                    .thenBy { it.displayName.lowercase() },
            )
            if (models.isNotEmpty()) {
                add(
                    ModelUsageProviderUi(
                        id = providerId,
                        name = provider.optString("name").ifBlank { providerId },
                        models = models,
                    ),
                )
            }
        }
    }.sortedBy { it.name.lowercase() }
    return ModelUsageSnapshot(providers = providers)
}

internal fun applyModelUsageDelta(raw: String?, delta: ModelUsageDelta): String {
    if (delta.providerId.isBlank() || delta.modelId.isBlank()) return raw.orEmpty()
    if (delta.inputTokens <= 0L && delta.outputTokens <= 0L && delta.conversationId.isNullOrBlank()) {
        return raw.orEmpty()
    }
    val root = runCatching { JSONObject(raw.takeUnless { it.isNullOrBlank() } ?: "{}") }
        .getOrDefault(JSONObject())
    val providers = root.optJSONObject("providers") ?: JSONObject().also {
        root.put("providers", it)
    }
    val provider = providers.optJSONObject(delta.providerId) ?: JSONObject().also {
        providers.put(delta.providerId, it)
    }
    provider.put("name", delta.providerName.ifBlank { delta.providerId })
    val models = provider.optJSONObject("models") ?: JSONObject().also {
        provider.put("models", it)
    }
    val model = models.optJSONObject(delta.modelId) ?: JSONObject().also {
        models.put(delta.modelId, it)
    }
    model.put("displayName", delta.modelDisplayName.ifBlank { delta.modelId })
    model.put("inputTokens", model.optLong("inputTokens") + delta.inputTokens.coerceAtLeast(0L))
    model.put("outputTokens", model.optLong("outputTokens") + delta.outputTokens.coerceAtLeast(0L))
    val conversations = stringSet(model.optJSONArray("conversations")).toMutableSet()
    delta.conversationId?.takeIf { it.isNotBlank() }?.let(conversations::add)
    model.put("conversations", JSONArray(conversations.sorted()))
    val days = stringSet(model.optJSONArray("days")).toMutableSet()
    days += delta.day.toString()
    model.put("days", JSONArray(days.sorted()))
    val events = decodeEvents(model.optJSONArray("events")).toMutableList()
    events += ModelUsageEvent(
        atMillis = delta.atMillis,
        inputTokens = delta.inputTokens.coerceAtLeast(0L),
        outputTokens = delta.outputTokens.coerceAtLeast(0L),
        conversationId = delta.conversationId,
    )
    val trimmed = if (events.size > MAX_MODEL_EVENTS) {
        events.takeLast(MAX_MODEL_EVENTS)
    } else {
        events
    }
    model.put("events", encodeEvents(trimmed))
    return root.toString()
}

private fun decodeEvents(array: JSONArray?): List<ModelUsageEvent> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val at = item.optLong("t")
            if (at <= 0L) continue
            add(
                ModelUsageEvent(
                    atMillis = at,
                    inputTokens = item.optLong("in"),
                    outputTokens = item.optLong("out"),
                    conversationId = item.optString("c").takeIf { it.isNotBlank() },
                ),
            )
        }
    }
}

private fun encodeEvents(events: List<ModelUsageEvent>): JSONArray =
    JSONArray().also { array ->
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("t", event.atMillis)
                    .put("in", event.inputTokens)
                    .put("out", event.outputTokens)
                    .put("c", event.conversationId.orEmpty()),
            )
        }
    }

private fun stringSet(array: JSONArray?): Set<String> {
    if (array == null) return emptySet()
    return buildSet {
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun eventDay(atMillis: Long): LocalDate =
    Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private const val MAX_MODEL_EVENTS = 4000


internal fun ModelUsageSnapshot.withBalanceConversions(
    conversions: Map<String, TokenBalanceConversion>,
): ModelUsageSnapshot = copy(
    providers = providers.map { provider ->
        val conversion = conversions[provider.id]
        provider.copy(
            models = provider.models.map { model ->
                model.copy(billedCredits = conversion?.creditsForInputTokens(model.inputTokens))
            },
        )
    },
)
