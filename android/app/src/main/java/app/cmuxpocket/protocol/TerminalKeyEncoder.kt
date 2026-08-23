package app.cmuxpocket.protocol

/**
 * Modifier set applied to a single key press from the built-in keyboard panel.
 *
 * - [ctrl]: maps letters to ASCII control codes (C0), e.g. Ctrl+C -> 0x03.
 * - [shift]: uppercases letters; Shift+Tab emits backtab.
 * - [alt]: Option; prefixes the sequence with ESC (meta).
 * - [meta]: Command; terminal-compatible meta encoding, also an ESC prefix,
 *   applied in addition to [alt] so both can be distinguished.
 */
data class TerminalModifiers(
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false
) {
    fun none(): Boolean = !ctrl && !shift && !alt && !meta
}

/** Modifier keys exposed by the built-in keyboard panel. */
enum class ModifierKey {
    CONTROL,
    SHIFT,
    OPTION,
    COMMAND
}

/** Non-printable keys with conventional terminal escape sequences. */
enum class SpecialKey {
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    INSERT, DELETE, HOME, END, PAGE_UP, PAGE_DOWN,
    UP, DOWN, LEFT, RIGHT,
    ESCAPE, TAB, BACKSPACE, ENTER
}

/**
 * Encodes key presses from the built-in keyboard panel into the byte
 * sequences a conventional xterm-compatible terminal expects.
 */
object TerminalKeyEncoder {

    private const val ESC = "\u001b"
    private const val DEL = "\u007f"

    /** Base (unmodified) sequence for a special key. */
    fun baseSequence(key: SpecialKey): String = when (key) {
        SpecialKey.F1 -> ESC + "OP"
        SpecialKey.F2 -> ESC + "OQ"
        SpecialKey.F3 -> ESC + "OR"
        SpecialKey.F4 -> ESC + "OS"
        SpecialKey.F5 -> ESC + "[15~"
        SpecialKey.F6 -> ESC + "[17~"
        SpecialKey.F7 -> ESC + "[18~"
        SpecialKey.F8 -> ESC + "[19~"
        SpecialKey.F9 -> ESC + "[20~"
        SpecialKey.F10 -> ESC + "[21~"
        SpecialKey.F11 -> ESC + "[23~"
        SpecialKey.F12 -> ESC + "[24~"
        SpecialKey.INSERT -> ESC + "[2~"
        SpecialKey.DELETE -> ESC + "[3~"
        SpecialKey.HOME -> ESC + "[H"
        SpecialKey.END -> ESC + "[F"
        SpecialKey.PAGE_UP -> ESC + "[5~"
        SpecialKey.PAGE_DOWN -> ESC + "[6~"
        SpecialKey.UP -> ESC + "[A"
        SpecialKey.DOWN -> ESC + "[B"
        SpecialKey.RIGHT -> ESC + "[C"
        SpecialKey.LEFT -> ESC + "[D"
        SpecialKey.ESCAPE -> ESC
        SpecialKey.TAB -> "\t"
        SpecialKey.BACKSPACE -> DEL
        SpecialKey.ENTER -> "\r"
    }

    /**
     * xterm modifier parameter: 1 + Shift(1) + Alt(2) + Ctrl(4) + Meta(8).
     * Only meaningful when at least one modifier is active (result >= 2).
     */
    fun modifierParam(mods: TerminalModifiers): Int {
        var param = 1
        if (mods.shift) param += 1
        if (mods.alt) param += 2
        if (mods.ctrl) param += 4
        if (mods.meta) param += 8
        return param
    }

    /** ASCII control code for Ctrl+<char>, or null when the char has none. */
    fun ctrlCode(ch: Char): Char? = when (val upper = ch.uppercaseChar()) {
        in 'A'..'Z' -> (upper.code - 'A'.code + 1).toChar()
        '@' -> '\u0000'
        '[' -> ESC[0]
        '\\' -> '\u001c'
        ']' -> '\u001d'
        '^' -> '\u001e'
        '_' -> '\u001f'
        '?' -> DEL[0]
        else -> null
    }

    /**
     * Encodes printable text with the given modifiers.
     * Shift uppercases letters; Ctrl maps the first character to its ASCII
     * control code when one exists; Option/Command prefix ESC.
     */
    fun encodePrintable(text: String, mods: TerminalModifiers): String {
        var out = text
        if (mods.shift) {
            out = buildString(out.length) {
                for (c in out) append(if (c.isLetter()) c.uppercaseChar() else c)
            }
        }
        if (mods.ctrl && out.isNotEmpty()) {
            val mapped = ctrlCode(out[0])
            if (mapped != null) {
                out = mapped + out.substring(1)
            }
        }
        if (mods.alt) out = ESC + out
        if (mods.meta) out = ESC + out
        return out
    }

    /**
     * Encodes a special key press with the given modifiers.
     * Keys with a conventional xterm modified form emit CSI/SS3 sequences
     * carrying [modifierParam]; the rest use conventional fallbacks
     * (Shift+Tab backtab, Ctrl+Backspace BS) plus ESC prefixes for
     * Option/Command.
     */
    fun encodeSpecial(key: SpecialKey, mods: TerminalModifiers): String {
        if (mods.none()) return baseSequence(key)

        modifiedSequence(key, mods)?.let { return it }

        var seq = when (key) {
            SpecialKey.TAB -> if (mods.shift && !mods.ctrl) ESC + "[Z" else baseSequence(key)
            SpecialKey.BACKSPACE -> if (mods.ctrl) "\u0008" else baseSequence(key)
            else -> baseSequence(key)
        }
        if (mods.alt) seq = ESC + seq
        if (mods.meta) seq = ESC + seq
        return seq
    }

    /** xterm modified sequence for keys that have one, else null. */
    private fun modifiedSequence(key: SpecialKey, mods: TerminalModifiers): String? {
        val p = modifierParam(mods)
        return when (key) {
            SpecialKey.UP -> ESC + "[1;${p}A"
            SpecialKey.DOWN -> ESC + "[1;${p}B"
            SpecialKey.RIGHT -> ESC + "[1;${p}C"
            SpecialKey.LEFT -> ESC + "[1;${p}D"
            SpecialKey.HOME -> ESC + "[1;${p}H"
            SpecialKey.END -> ESC + "[1;${p}F"
            SpecialKey.INSERT -> ESC + "[2;${p}~"
            SpecialKey.DELETE -> ESC + "[3;${p}~"
            SpecialKey.PAGE_UP -> ESC + "[5;${p}~"
            SpecialKey.PAGE_DOWN -> ESC + "[6;${p}~"
            SpecialKey.F1 -> ESC + "[1;${p}P"
            SpecialKey.F2 -> ESC + "[1;${p}Q"
            SpecialKey.F3 -> ESC + "[1;${p}R"
            SpecialKey.F4 -> ESC + "[1;${p}S"
            SpecialKey.F5 -> ESC + "[15;${p}~"
            SpecialKey.F6 -> ESC + "[17;${p}~"
            SpecialKey.F7 -> ESC + "[18;${p}~"
            SpecialKey.F8 -> ESC + "[19;${p}~"
            SpecialKey.F9 -> ESC + "[20;${p}~"
            SpecialKey.F10 -> ESC + "[21;${p}~"
            SpecialKey.F11 -> ESC + "[23;${p}~"
            SpecialKey.F12 -> ESC + "[24;${p}~"
            else -> null
        }
    }
}

/**
 * Tracks modifier selection for the built-in keyboard panel.
 *
 * Default behavior is one-shot: selected modifiers apply to the next key
 * press and auto-release after it is sent ([onKeySent]). When Combination
 * Mode is enabled, selected modifiers stay latched until toggled off
 * individually.
 */
class KeyboardModifierController {

    var modifiers: Set<ModifierKey> = emptySet()
        private set

    var combinationMode: Boolean = false
        private set

    fun toggle(key: ModifierKey) {
        modifiers = if (key in modifiers) modifiers - key else modifiers + key
    }

    fun setCombinationMode(enabled: Boolean) {
        combinationMode = enabled
    }

    /** Modifier snapshot to apply when encoding the next key press. */
    fun currentModifiers(): TerminalModifiers = TerminalModifiers(
        ctrl = ModifierKey.CONTROL in modifiers,
        shift = ModifierKey.SHIFT in modifiers,
        alt = ModifierKey.OPTION in modifiers,
        meta = ModifierKey.COMMAND in modifiers
    )

    /** Call after a key press has been sent; releases one-shot modifiers. */
    fun onKeySent() {
        if (!combinationMode) {
            modifiers = emptySet()
        }
    }

    fun clear() {
        modifiers = emptySet()
    }
}
