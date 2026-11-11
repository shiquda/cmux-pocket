package app.cmuxpocket

import app.cmuxpocket.protocol.SpecialKey
import app.cmuxpocket.protocol.TerminalModifiers
import app.cmuxpocket.ui.AccessoryKeyCatalog
import app.cmuxpocket.ui.AccessoryKeyItem
import app.cmuxpocket.ui.AccessoryKeyStyle
import app.cmuxpocket.ui.AccessoryPresetCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryKeyboardConfigTest {

    @Test
    fun defaultKeysPreserveOriginalTwelveKeysAndStyles() {
        val defaults = AccessoryKeyCatalog.defaultKeys()
        assertEquals(
            listOf("ESC", "Enter", "TAB", "^C", "↑", "↓", "←", "→", "|", "/", "-", "~"),
            defaults.map { it.label }
        )
        assertEquals(
            listOf("\u001b", "\r", "\t", "\u0003", "\u001b[A", "\u001b[B", "\u001b[D", "\u001b[C", "|", "/", "-", "~"),
            defaults.map { it.sequence }
        )
        assertEquals(AccessoryKeyStyle.PRIMARY, defaults[0].style)
        assertEquals(AccessoryKeyStyle.PRIMARY, defaults[1].style)
        assertEquals(AccessoryKeyStyle.NORMAL, defaults[2].style)
        assertEquals(AccessoryKeyStyle.SPECIAL, defaults[3].style)
        assertTrue(AccessoryKeyCatalog.isDefaultLayout(defaults))
    }

    @Test
    fun decodeMissingOrCorruptReturnsDefaultsWhileExplicitEmptySurvives() {
        assertEquals(
            AccessoryKeyCatalog.defaultKeys().map { it.sequence },
            AccessoryKeyCatalog.decode(null).map { it.sequence }
        )
        assertEquals(
            AccessoryKeyCatalog.defaultKeys().map { it.sequence },
            AccessoryKeyCatalog.decode("{not-json").map { it.sequence }
        )
        val emptyEncoded = AccessoryKeyCatalog.encode(emptyList())
        assertTrue(AccessoryKeyCatalog.decode(emptyEncoded).isEmpty())
    }

    @Test
    fun decodeFiltersBrokenItemsAndRoundTripsCustomCombos() {
        val custom = listOf(
            AccessoryKeyCatalog.fromSpecial(SpecialKey.TAB, TerminalModifiers(shift = true)),
            AccessoryKeyCatalog.fromPrintable("d", TerminalModifiers(ctrl = true)),
            AccessoryKeyCatalog.fromSpecial(SpecialKey.LEFT, TerminalModifiers(alt = true)),
            AccessoryKeyCatalog.fromPrintable("k", TerminalModifiers(meta = true))
        )
        val encoded = AccessoryKeyCatalog.encode(custom)
        val decoded = AccessoryKeyCatalog.decode(encoded)

        assertEquals(4, decoded.size)
        assertEquals("⇧Tab", decoded[0].label)
        assertEquals("\u001b[Z", decoded[0].sequence)
        assertEquals("^D", decoded[1].label)
        assertEquals("\u0004", decoded[1].sequence)
        assertEquals("⌥←", decoded[2].label)
        assertEquals("\u001b[1;3D", decoded[2].sequence)
        assertEquals("⌘K", decoded[3].label)
        assertEquals("\u001bk", decoded[3].sequence)

        val brokenPayload = """[{"id":"","label":"   ","sequence":"x"},{"id":"","label":"^D","sequence":"\u0004"}]"""
        val sanitized = AccessoryKeyCatalog.decode(brokenPayload)
        assertEquals(1, sanitized.size)
        assertEquals("^D", sanitized[0].label)
        assertTrue(sanitized[0].id.isNotBlank())
    }

    @Test
    fun toggleAndAddDeduplicateEquivalentKeys() {
        val defaults = AccessoryKeyCatalog.defaultKeys()
        val uppercaseCtrlC = AccessoryKeyCatalog.fromPrintable("C", TerminalModifiers(ctrl = true))
        val shiftCtrlC = AccessoryKeyCatalog.fromPrintable("c", TerminalModifiers(ctrl = true, shift = true))
        val uppercaseAFromShift = AccessoryKeyCatalog.fromPrintable("a", TerminalModifiers(shift = true))
        val directUppercaseA = AccessoryKeyCatalog.fromPrintable("A")

        // Equivalent Ctrl+C variants match existing ^C
        assertTrue(AccessoryKeyCatalog.containsKey(defaults, uppercaseCtrlC))
        assertTrue(AccessoryKeyCatalog.containsKey(defaults, shiftCtrlC))

        val (afterDupAdd, wasAdded) = AccessoryKeyCatalog.addIfAbsent(defaults, uppercaseCtrlC)
        assertFalse(wasAdded)
        assertEquals(defaults.size, afterDupAdd.size)

        // Adding Shift+Tab appends it; toggling removes it
        val shiftTab = AccessoryKeyCatalog.fromSpecial(SpecialKey.TAB, TerminalModifiers(shift = true))
        val withShiftTab = AccessoryKeyCatalog.togglePreset(defaults, shiftTab)
        assertEquals(defaults.size + 1, withShiftTab.size)
        assertFalse(AccessoryKeyCatalog.isDefaultLayout(withShiftTab))

        val withoutShiftTab = AccessoryKeyCatalog.togglePreset(withShiftTab, shiftTab)
        assertEquals(defaults.size, withoutShiftTab.size)
        assertTrue(AccessoryKeyCatalog.isDefaultLayout(withoutShiftTab))

        // Shift+a and literal A share canonical identity
        assertTrue(AccessoryKeyCatalog.sameKey(uppercaseAFromShift, directUppercaseA))
    }

    @Test
    fun moveAndRemoveHandleBoundsSafely() {
        val keys = listOf(
            AccessoryKeyItem(id = "1", label = "A", sequence = "a"),
            AccessoryKeyItem(id = "2", label = "B", sequence = "b"),
            AccessoryKeyItem(id = "3", label = "C", sequence = "c")
        )
        assertEquals(listOf("B", "A", "C"), AccessoryKeyCatalog.moveKey(keys, 0, 1).map { it.label })
        assertEquals(listOf("A", "B", "C"), AccessoryKeyCatalog.moveKey(keys, 0, -1).map { it.label })
        assertEquals(listOf("A", "C"), AccessoryKeyCatalog.removeKeyAt(keys, 1).map { it.label })
        assertEquals(keys, AccessoryKeyCatalog.removeKeyAt(keys, 99))
    }

    @Test
    fun commonPresetsCoverAllCategoriesAndUniqueCanonicalIds() {
        val presets = AccessoryKeyCatalog.commonPresets
        assertEquals(
            AccessoryPresetCategory.entries.toSet(),
            presets.map { it.category }.toSet()
        )
        val canonicalIds = presets.mapNotNull { it.item.canonicalId }
        assertEquals(canonicalIds.size, canonicalIds.toSet().size)
    }
}
