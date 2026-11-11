package app.cmuxpocket.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A user-saved terminal command or prompt phrase stored locally on the device
 * and shared across all configured Mac hosts.
 */
@Serializable
data class CommandPhrase(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String,
    val autoEnter: Boolean = true
) {
    /**
     * Compact single-line label rendered inside the Level 1 quick phrase strip.
     * Uses the optional [title] when present; otherwise collapses newlines in [content].
     */
    fun chipText(): String {
        val cleanTitle = title.trim()
        if (cleanTitle.isNotEmpty()) return cleanTitle
        val condensed = content.replace(Regex("[\\r\\n]+"), " ").trim()
        return condensed.ifEmpty { content }
    }
}

private val phraseJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun encodeCommandPhrases(phrases: List<CommandPhrase>): String =
    phraseJson.encodeToString(phrases)

internal fun decodeCommandPhrases(raw: String?): List<CommandPhrase> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        phraseJson.decodeFromString<List<CommandPhrase>>(raw)
            .filter { it.content.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Inserts a new phrase at the end or replaces an existing phrase with the same [CommandPhrase.id]
 * in place. Meaningful leading/trailing whitespace in [CommandPhrase.content] (such as `"cd "`)
 * is preserved verbatim; blank-only content is ignored.
 */
internal fun upsertCommandPhrase(
    phrases: List<CommandPhrase>,
    phrase: CommandPhrase
): List<CommandPhrase> {
    if (phrase.content.isBlank()) return phrases
    val normalized = phrase.copy(title = phrase.title.trim())
    val existingIndex = phrases.indexOfFirst { it.id == normalized.id }
    return if (existingIndex >= 0) {
        phrases.toMutableList().apply { set(existingIndex, normalized) }
    } else {
        phrases + normalized
    }
}

internal fun deleteCommandPhrase(
    phrases: List<CommandPhrase>,
    id: String
): List<CommandPhrase> = phrases.filterNot { it.id == id }

internal fun moveCommandPhrase(
    phrases: List<CommandPhrase>,
    id: String,
    delta: Int
): List<CommandPhrase> {
    if (delta == 0 || phrases.size < 2) return phrases
    val index = phrases.indexOfFirst { it.id == id }
    if (index < 0) return phrases
    val targetIndex = (index + delta).coerceIn(0, phrases.lastIndex)
    if (targetIndex == index) return phrases
    val mutable = phrases.toMutableList()
    val item = mutable.removeAt(index)
    mutable.add(targetIndex, item)
    return mutable
}
