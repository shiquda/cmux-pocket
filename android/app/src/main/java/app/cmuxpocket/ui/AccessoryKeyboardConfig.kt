package app.cmuxpocket.ui

import app.cmuxpocket.protocol.SpecialKey
import app.cmuxpocket.protocol.TerminalKeyEncoder
import app.cmuxpocket.protocol.TerminalModifiers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class AccessoryKeyStyle {
    NORMAL,
    PRIMARY,
    SPECIAL
}

/**
 * A single button rendered in the bottom [AccessoryKeyboardBar].
 *
 * [sequence] is pre-encoded at configuration time so terminal frame recompositions
 * never re-run [TerminalKeyEncoder]. [canonicalId] identifies equivalent keys/combos
 * across presets and picker deduplication.
 */
@Serializable
data class AccessoryKeyItem(
    val id: String,
    val label: String,
    val sequence: String,
    val style: AccessoryKeyStyle = AccessoryKeyStyle.NORMAL,
    val canonicalId: String? = null
)

enum class AccessoryPresetCategory {
    ESSENTIAL_AND_NAVIGATION,
    CONTROL_AND_COMBOS,
    SYMBOLS,
    FUNCTION_KEYS
}

data class AccessoryPresetOption(
    val category: AccessoryPresetCategory,
    val item: AccessoryKeyItem
)

object AccessoryKeyCatalog {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Exact default 12-key layout that cmux Pocket ships with.
     */
    fun defaultKeys(): List<AccessoryKeyItem> = listOf(
        fromSpecial(SpecialKey.ESCAPE, id = "default_esc"),
        fromSpecial(SpecialKey.ENTER, id = "default_enter"),
        fromSpecial(SpecialKey.TAB, id = "default_tab"),
        fromPrintable("c", TerminalModifiers(ctrl = true), id = "default_ctrl_c"),
        fromSpecial(SpecialKey.UP, id = "default_up"),
        fromSpecial(SpecialKey.DOWN, id = "default_down"),
        fromSpecial(SpecialKey.LEFT, id = "default_left"),
        fromSpecial(SpecialKey.RIGHT, id = "default_right"),
        fromPrintable("|", id = "default_pipe"),
        fromPrintable("/", id = "default_slash"),
        fromPrintable("-", id = "default_dash"),
        fromPrintable("~", id = "default_tilde")
    )

    val commonPresets: List<AccessoryPresetOption> by lazy {
        buildList {
            // Essential & Navigation
            listOf(
                fromSpecial(SpecialKey.ESCAPE, id = "preset_esc"),
                fromSpecial(SpecialKey.ENTER, id = "preset_enter"),
                fromSpecial(SpecialKey.TAB, id = "preset_tab"),
                fromSpecial(SpecialKey.TAB, TerminalModifiers(shift = true), id = "preset_shift_tab"),
                fromSpecial(SpecialKey.UP, id = "preset_up"),
                fromSpecial(SpecialKey.DOWN, id = "preset_down"),
                fromSpecial(SpecialKey.LEFT, id = "preset_left"),
                fromSpecial(SpecialKey.RIGHT, id = "preset_right"),
                fromSpecial(SpecialKey.HOME, id = "preset_home"),
                fromSpecial(SpecialKey.END, id = "preset_end"),
                fromSpecial(SpecialKey.PAGE_UP, id = "preset_pgup"),
                fromSpecial(SpecialKey.PAGE_DOWN, id = "preset_pgdn"),
                fromSpecial(SpecialKey.DELETE, id = "preset_del"),
                fromSpecial(SpecialKey.INSERT, id = "preset_ins"),
                fromSpecial(SpecialKey.BACKSPACE, id = "preset_bs"),
                fromPrintable(" ", id = "preset_space")
            ).forEach { add(AccessoryPresetOption(AccessoryPresetCategory.ESSENTIAL_AND_NAVIGATION, it)) }

            // Control & Common Combos
            listOf(
                fromPrintable("c", TerminalModifiers(ctrl = true), id = "preset_ctrl_c"),
                fromPrintable("d", TerminalModifiers(ctrl = true), id = "preset_ctrl_d"),
                fromPrintable("z", TerminalModifiers(ctrl = true), id = "preset_ctrl_z"),
                fromPrintable("l", TerminalModifiers(ctrl = true), id = "preset_ctrl_l"),
                fromPrintable("a", TerminalModifiers(ctrl = true), id = "preset_ctrl_a"),
                fromPrintable("e", TerminalModifiers(ctrl = true), id = "preset_ctrl_e"),
                fromPrintable("r", TerminalModifiers(ctrl = true), id = "preset_ctrl_r"),
                fromPrintable("w", TerminalModifiers(ctrl = true), id = "preset_ctrl_w"),
                fromPrintable("u", TerminalModifiers(ctrl = true), id = "preset_ctrl_u"),
                fromPrintable("k", TerminalModifiers(ctrl = true), id = "preset_ctrl_k"),
                fromPrintable("b", TerminalModifiers(ctrl = true), id = "preset_ctrl_b"),
                fromPrintable("o", TerminalModifiers(ctrl = true), id = "preset_ctrl_o"),
                fromSpecial(SpecialKey.LEFT, TerminalModifiers(alt = true), id = "preset_opt_left"),
                fromSpecial(SpecialKey.RIGHT, TerminalModifiers(alt = true), id = "preset_opt_right"),
                fromSpecial(SpecialKey.LEFT, TerminalModifiers(ctrl = true), id = "preset_ctrl_left"),
                fromSpecial(SpecialKey.RIGHT, TerminalModifiers(ctrl = true), id = "preset_ctrl_right"),
                fromSpecial(SpecialKey.BACKSPACE, TerminalModifiers(alt = true), id = "preset_opt_bs"),
                fromSpecial(SpecialKey.BACKSPACE, TerminalModifiers(ctrl = true), id = "preset_ctrl_bs")
            ).forEach { add(AccessoryPresetOption(AccessoryPresetCategory.CONTROL_AND_COMBOS, it)) }

            // Symbols
            listOf(
                "|", "/", "-", "~", "\\", "_", ".", ":", ";", "\"", "'", "`",
                "$", "&", "*", "?", "=", "+", "[", "]", "{", "}", "(", ")", "<", ">"
            ).forEachIndexed { idx, sym ->
                add(
                    AccessoryPresetOption(
                        AccessoryPresetCategory.SYMBOLS,
                        fromPrintable(sym, id = "preset_sym_$idx")
                    )
                )
            }

            // Function Keys
            listOf(
                SpecialKey.F1, SpecialKey.F2, SpecialKey.F3, SpecialKey.F4,
                SpecialKey.F5, SpecialKey.F6, SpecialKey.F7, SpecialKey.F8,
                SpecialKey.F9, SpecialKey.F10, SpecialKey.F11, SpecialKey.F12
            ).forEach { fKey ->
                add(
                    AccessoryPresetOption(
                        AccessoryPresetCategory.FUNCTION_KEYS,
                        fromSpecial(fKey, id = "preset_${fKey.name.lowercase()}")
                    )
                )
            }
        }
    }

    fun fromSpecial(
        key: SpecialKey,
        mods: TerminalModifiers = TerminalModifiers(),
        id: String = nextId()
    ): AccessoryKeyItem {
        val sequence = TerminalKeyEncoder.encodeSpecial(key, mods)
        val label = formatSpecialLabel(key, mods)
        val style = when {
            mods.none() && (key == SpecialKey.ESCAPE || key == SpecialKey.ENTER) -> AccessoryKeyStyle.PRIMARY
            else -> AccessoryKeyStyle.NORMAL
        }
        return AccessoryKeyItem(
            id = id,
            label = label,
            sequence = sequence,
            style = style,
            canonicalId = canonicalSpecialId(key, mods)
        )
    }

    fun fromPrintable(
        rawText: String,
        mods: TerminalModifiers = TerminalModifiers(),
        id: String = nextId()
    ): AccessoryKeyItem {
        val normalizedMods = if (mods.ctrl && mods.shift && rawText.length == 1 && rawText[0].isLetter()) {
            // Standard C0 control codes fold Shift+Ctrl+letter into Ctrl+letter.
            mods.copy(shift = false)
        } else {
            mods
        }
        val sequence = TerminalKeyEncoder.encodePrintable(rawText, normalizedMods)
        val label = formatPrintableLabel(rawText, normalizedMods)
        val isPlainCtrlC = rawText.equals("c", ignoreCase = true) &&
            normalizedMods == TerminalModifiers(ctrl = true)
        val style = if (isPlainCtrlC) AccessoryKeyStyle.SPECIAL else AccessoryKeyStyle.NORMAL
        return AccessoryKeyItem(
            id = id,
            label = label,
            sequence = sequence,
            style = style,
            canonicalId = canonicalPrintableId(rawText, normalizedMods)
        )
    }

    fun formatSpecialLabel(key: SpecialKey, mods: TerminalModifiers): String {
        val base = if (mods.none()) {
            when (key) {
                SpecialKey.ESCAPE -> "ESC"
                SpecialKey.ENTER -> "Enter"
                SpecialKey.TAB -> "TAB"
                SpecialKey.BACKSPACE -> "⌫"
                SpecialKey.INSERT -> "Ins"
                SpecialKey.DELETE -> "Del"
                SpecialKey.HOME -> "Home"
                SpecialKey.END -> "End"
                SpecialKey.PAGE_UP -> "PgUp"
                SpecialKey.PAGE_DOWN -> "PgDn"
                SpecialKey.UP -> "↑"
                SpecialKey.DOWN -> "↓"
                SpecialKey.LEFT -> "←"
                SpecialKey.RIGHT -> "→"
                SpecialKey.F1 -> "F1"
                SpecialKey.F2 -> "F2"
                SpecialKey.F3 -> "F3"
                SpecialKey.F4 -> "F4"
                SpecialKey.F5 -> "F5"
                SpecialKey.F6 -> "F6"
                SpecialKey.F7 -> "F7"
                SpecialKey.F8 -> "F8"
                SpecialKey.F9 -> "F9"
                SpecialKey.F10 -> "F10"
                SpecialKey.F11 -> "F11"
                SpecialKey.F12 -> "F12"
            }
        } else {
            when (key) {
                SpecialKey.ESCAPE -> "Esc"
                SpecialKey.ENTER -> "⏎"
                SpecialKey.TAB -> "Tab"
                SpecialKey.BACKSPACE -> "⌫"
                SpecialKey.INSERT -> "Ins"
                SpecialKey.DELETE -> "Del"
                SpecialKey.HOME -> "Home"
                SpecialKey.END -> "End"
                SpecialKey.PAGE_UP -> "PgUp"
                SpecialKey.PAGE_DOWN -> "PgDn"
                SpecialKey.UP -> "↑"
                SpecialKey.DOWN -> "↓"
                SpecialKey.LEFT -> "←"
                SpecialKey.RIGHT -> "→"
                else -> key.name
            }
        }
        return modifierPrefix(mods) + base
    }

    fun formatPrintableLabel(rawText: String, mods: TerminalModifiers): String {
        if (mods.none()) {
            return if (rawText == " ") "Space" else rawText
        }
        if (mods == TerminalModifiers(shift = true) && rawText.length == 1 && rawText[0].isLetter()) {
            return rawText.uppercase()
        }
        if (mods == TerminalModifiers(ctrl = true)) {
            val ch = if (rawText == " ") "␣" else rawText.uppercase()
            return "^$ch"
        }
        val ch = if (rawText == " ") {
            "␣"
        } else if (rawText.length == 1 && rawText[0].isLetter()) {
            rawText.uppercase()
        } else {
            rawText
        }
        return modifierPrefix(mods) + ch
    }

    fun modifierPrefix(mods: TerminalModifiers): String = buildString {
        if (mods.ctrl) append('⌃')
        if (mods.alt) append('⌥')
        if (mods.shift) append('⇧')
        if (mods.meta) append('⌘')
    }

    fun canonicalSpecialId(key: SpecialKey, mods: TerminalModifiers): String {
        val tag = modifierTag(mods)
        return if (tag.isEmpty()) "special:${key.name}" else "special:${key.name}:$tag"
    }

    fun canonicalPrintableId(rawText: String, mods: TerminalModifiers): String {
        if (mods == TerminalModifiers(shift = true) && rawText.length == 1 && rawText[0].isLetter()) {
            return "printable:char:${rawText.uppercase()}"
        }
        if (mods.none()) {
            return if (rawText == " ") "printable:space" else "printable:char:$rawText"
        }
        val keyPart = when {
            rawText == " " -> "space"
            rawText.length == 1 && rawText[0].isLetter() -> rawText.lowercase()
            else -> rawText
        }
        return "printable:$keyPart:${modifierTag(mods)}"
    }

    private fun modifierTag(mods: TerminalModifiers): String = buildList {
        if (mods.ctrl) add("c")
        if (mods.alt) add("a")
        if (mods.shift) add("s")
        if (mods.meta) add("m")
    }.joinToString("+")

    fun sameKey(a: AccessoryKeyItem, b: AccessoryKeyItem): Boolean {
        if (a.canonicalId != null && b.canonicalId != null) {
            return a.canonicalId == b.canonicalId
        }
        return a.sequence == b.sequence && a.label == b.label
    }

    fun containsKey(list: List<AccessoryKeyItem>, candidate: AccessoryKeyItem): Boolean =
        list.any { sameKey(it, candidate) }

    fun togglePreset(list: List<AccessoryKeyItem>, preset: AccessoryKeyItem): List<AccessoryKeyItem> {
        return if (containsKey(list, preset)) {
            list.filterNot { sameKey(it, preset) }
        } else {
            list + preset.copy(id = nextId())
        }
    }

    fun addIfAbsent(list: List<AccessoryKeyItem>, candidate: AccessoryKeyItem): Pair<List<AccessoryKeyItem>, Boolean> {
        if (containsKey(list, candidate)) return list to false
        return (list + candidate.copy(id = nextId())) to true
    }

    fun moveKey(list: List<AccessoryKeyItem>, fromIndex: Int, toIndex: Int): List<AccessoryKeyItem> {
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return list
        val mutable = list.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return mutable
    }

    fun removeKeyAt(list: List<AccessoryKeyItem>, index: Int): List<AccessoryKeyItem> {
        if (index !in list.indices) return list
        return list.filterIndexed { i, _ -> i != index }
    }

    fun isDefaultLayout(list: List<AccessoryKeyItem>): Boolean {
        val defaults = defaultKeys()
        if (list.size != defaults.size) return false
        return list.indices.all { sameKey(list[it], defaults[it]) }
    }

    fun encode(items: List<AccessoryKeyItem>): String = json.encodeToString(items)

    fun decode(raw: String?): List<AccessoryKeyItem> {
        if (raw == null) return defaultKeys()
        return try {
            val parsed = json.decodeFromString<List<AccessoryKeyItem>>(raw)
            parsed.mapNotNull { item ->
                val cleanLabel = item.label.trim()
                if (cleanLabel.isEmpty() || item.sequence.isEmpty()) return@mapNotNull null
                item.copy(
                    id = item.id.ifBlank { nextId() },
                    label = cleanLabel
                )
            }
        } catch (_: Exception) {
            defaultKeys()
        }
    }

    private fun nextId(): String = "k_${UUID.randomUUID()}"
}
