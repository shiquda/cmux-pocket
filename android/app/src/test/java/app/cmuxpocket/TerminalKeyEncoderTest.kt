package app.cmuxpocket

import app.cmuxpocket.protocol.KeyboardModifierController
import app.cmuxpocket.protocol.ModifierKey
import app.cmuxpocket.protocol.SpecialKey
import app.cmuxpocket.protocol.TerminalKeyEncoder
import app.cmuxpocket.protocol.TerminalModifiers
import org.junit.Assert.*
import org.junit.Test

class TerminalKeyEncoderTest {

    private val esc = "\u001b"

    // ---- Ctrl composition ----

    @Test
    fun ctrlLetterMapsToAsciiControlCode() {
        val encoded = TerminalKeyEncoder.encodePrintable("c", TerminalModifiers(ctrl = true))
        assertEquals("\u0003", encoded) // Ctrl+C -> ETX
    }

    @Test
    fun ctrlUppercaseLetterMapsToSameControlCode() {
        val encoded = TerminalKeyEncoder.encodePrintable("C", TerminalModifiers(ctrl = true))
        assertEquals("\u0003", encoded)
    }

    @Test
    fun ctrlDProducesEot() {
        val encoded = TerminalKeyEncoder.encodePrintable("d", TerminalModifiers(ctrl = true))
        assertEquals("\u0004", encoded)
    }

    @Test
    fun ctrlBracketMapsToEscape() {
        val encoded = TerminalKeyEncoder.encodePrintable("[", TerminalModifiers(ctrl = true))
        assertEquals(esc, encoded)
    }

    @Test
    fun ctrlOnlyMapsFirstCharacterOfChunk() {
        val encoded = TerminalKeyEncoder.encodePrintable("abc", TerminalModifiers(ctrl = true))
        assertEquals("\u0001bc", encoded)
    }

    @Test
    fun ctrlUnmappedCharacterPassesThrough() {
        val encoded = TerminalKeyEncoder.encodePrintable("5", TerminalModifiers(ctrl = true))
        assertEquals("5", encoded)
    }

    // ---- Shift casing ----

    @Test
    fun shiftUppercasesLetters() {
        val encoded = TerminalKeyEncoder.encodePrintable("a", TerminalModifiers(shift = true))
        assertEquals("A", encoded)
    }

    @Test
    fun shiftLeavesDigitsUnchanged() {
        val encoded = TerminalKeyEncoder.encodePrintable("1", TerminalModifiers(shift = true))
        assertEquals("1", encoded)
    }

    @Test
    fun shiftTabEmitsBacktab() {
        val encoded = TerminalKeyEncoder.encodeSpecial(SpecialKey.TAB, TerminalModifiers(shift = true))
        assertEquals(esc + "[Z", encoded)
    }

    // ---- Option / Command prefixes ----

    @Test
    fun optionPrefixesEsc() {
        val encoded = TerminalKeyEncoder.encodePrintable("b", TerminalModifiers(alt = true))
        assertEquals(esc + "b", encoded)
    }

    @Test
    fun commandPrefixesEscSeparatelyFromOption() {
        val both = TerminalKeyEncoder.encodePrintable("b", TerminalModifiers(alt = true, meta = true))
        assertEquals(esc + esc + "b", both)
        val metaOnly = TerminalKeyEncoder.encodePrintable("b", TerminalModifiers(meta = true))
        assertEquals(esc + "b", metaOnly)
    }

    @Test
    fun optionLeftUsesXtermModifiedArrowSequence() {
        val encoded = TerminalKeyEncoder.encodeSpecial(SpecialKey.LEFT, TerminalModifiers(alt = true))
        assertEquals(esc + "[1;3D", encoded)
    }

    // ---- Special key sequences ----

    @Test
    fun unmodifiedSpecialKeysUseConventionalSequences() {
        assertEquals(esc + "OP", TerminalKeyEncoder.encodeSpecial(SpecialKey.F1, TerminalModifiers()))
        assertEquals(esc + "[H", TerminalKeyEncoder.encodeSpecial(SpecialKey.HOME, TerminalModifiers()))
        assertEquals(esc + "[5~", TerminalKeyEncoder.encodeSpecial(SpecialKey.PAGE_UP, TerminalModifiers()))
        assertEquals(esc + "[6~", TerminalKeyEncoder.encodeSpecial(SpecialKey.PAGE_DOWN, TerminalModifiers()))
        assertEquals(esc + "[3~", TerminalKeyEncoder.encodeSpecial(SpecialKey.DELETE, TerminalModifiers()))
        assertEquals(esc + "[2~", TerminalKeyEncoder.encodeSpecial(SpecialKey.INSERT, TerminalModifiers()))
        assertEquals(esc + "[F", TerminalKeyEncoder.encodeSpecial(SpecialKey.END, TerminalModifiers()))
        assertEquals(esc, TerminalKeyEncoder.encodeSpecial(SpecialKey.ESCAPE, TerminalModifiers()))
        assertEquals("\t", TerminalKeyEncoder.encodeSpecial(SpecialKey.TAB, TerminalModifiers()))
        assertEquals("\u007f", TerminalKeyEncoder.encodeSpecial(SpecialKey.BACKSPACE, TerminalModifiers()))
        assertEquals("\r", TerminalKeyEncoder.encodeSpecial(SpecialKey.ENTER, TerminalModifiers()))
    }

    @Test
    fun fKeyModifiedSequenceCarriesModifierParam() {
        // Ctrl -> param 5
        assertEquals(esc + "[1;5P", TerminalKeyEncoder.encodeSpecial(SpecialKey.F1, TerminalModifiers(ctrl = true)))
    }

    @Test
    fun homeWithShiftCarriesModifierParam() {
        // Shift -> param 2
        assertEquals(esc + "[1;2H", TerminalKeyEncoder.encodeSpecial(SpecialKey.HOME, TerminalModifiers(shift = true)))
    }

    @Test
    fun ctrlArrowCarriesModifierParam() {
        assertEquals(esc + "[1;5C", TerminalKeyEncoder.encodeSpecial(SpecialKey.RIGHT, TerminalModifiers(ctrl = true)))
    }

    @Test
    fun modifierParamCombinesShiftAltCtrlMeta() {
        assertEquals(2, TerminalKeyEncoder.modifierParam(TerminalModifiers(shift = true)))
        assertEquals(3, TerminalKeyEncoder.modifierParam(TerminalModifiers(alt = true)))
        assertEquals(5, TerminalKeyEncoder.modifierParam(TerminalModifiers(ctrl = true)))
        assertEquals(9, TerminalKeyEncoder.modifierParam(TerminalModifiers(meta = true)))
        assertEquals(16, TerminalKeyEncoder.modifierParam(TerminalModifiers(shift = true, alt = true, ctrl = true, meta = true)))
    }

    @Test
    fun plainTextWithoutModifiersPassesThrough() {
        assertEquals("hello", TerminalKeyEncoder.encodePrintable("hello", TerminalModifiers()))
    }
}

class KeyboardModifierControllerTest {

    // ---- One-shot (default) ----

    @Test
    fun oneShotModifiersAutoReleaseAfterKeySent() {
        val controller = KeyboardModifierController()
        controller.toggle(ModifierKey.CONTROL)
        assertTrue(controller.currentModifiers().ctrl)

        controller.onKeySent()

        assertFalse(controller.currentModifiers().ctrl)
        assertTrue(controller.modifiers.isEmpty())
    }

    @Test
    fun oneShotReleasesAllSelectedModifiers() {
        val controller = KeyboardModifierController()
        controller.toggle(ModifierKey.CONTROL)
        controller.toggle(ModifierKey.SHIFT)
        controller.onKeySent()

        assertTrue(controller.modifiers.isEmpty())
    }

    @Test
    fun toggleTwiceDeselectsBeforeKeySent() {
        val controller = KeyboardModifierController()
        controller.toggle(ModifierKey.OPTION)
        controller.toggle(ModifierKey.OPTION)
        assertTrue(controller.modifiers.isEmpty())
    }

    // ---- Combination Mode (latched) ----

    @Test
    fun combinationModeKeepsModifiersLatchedAfterKeySent() {
        val controller = KeyboardModifierController()
        controller.setCombinationMode(true)
        controller.toggle(ModifierKey.CONTROL)

        controller.onKeySent()
        assertTrue(controller.currentModifiers().ctrl)

        controller.onKeySent()
        assertTrue(controller.currentModifiers().ctrl)

        controller.toggle(ModifierKey.CONTROL)
        assertFalse(controller.currentModifiers().ctrl)
    }

    @Test
    fun disablingCombinationModeDoesNotClearCurrentSelection() {
        val controller = KeyboardModifierController()
        controller.setCombinationMode(true)
        controller.toggle(ModifierKey.SHIFT)
        controller.setCombinationMode(false)

        assertTrue(controller.currentModifiers().shift)
        controller.onKeySent()
        assertFalse(controller.currentModifiers().shift)
    }

    @Test
    fun modifiersSnapshotMapsAllFourKeys() {
        val controller = KeyboardModifierController()
        controller.toggle(ModifierKey.CONTROL)
        controller.toggle(ModifierKey.SHIFT)
        controller.toggle(ModifierKey.OPTION)
        controller.toggle(ModifierKey.COMMAND)

        val mods = controller.currentModifiers()
        assertTrue(mods.ctrl)
        assertTrue(mods.shift)
        assertTrue(mods.alt)
        assertTrue(mods.meta)
    }

    // ---- Composition end-to-end through the encoder ----

    @Test
    fun ctrlCCompositionThroughController() {
        val controller = KeyboardModifierController()
        controller.toggle(ModifierKey.CONTROL)

        val sent = TerminalKeyEncoder.encodePrintable("c", controller.currentModifiers())
        controller.onKeySent()

        assertEquals("\u0003", sent)
        assertTrue(controller.modifiers.isEmpty())
    }

    @Test
    fun shiftACompositionThroughController() {
        val controller = KeyboardModifierController()
        controller.toggle(ModifierKey.SHIFT)

        val sent = TerminalKeyEncoder.encodePrintable("a", controller.currentModifiers())
        controller.onKeySent()

        assertEquals("A", sent)
        // next key is unmodified after one-shot release
        val next = TerminalKeyEncoder.encodePrintable("a", controller.currentModifiers())
        assertEquals("a", next)
    }
}
