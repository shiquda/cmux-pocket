package app.cmuxpocket

import app.cmuxpocket.ui.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    @Test
    fun `fresh install follows the device locale`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.DEFAULT)
    }

    @Test
    fun `unknown or missing tag follows the device locale`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("fr"))
    }
    @Test
    fun `persisted tags round trip`() {
        AppLanguage.values().forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }
    @Test
    fun `system resolves to the closest shipped dictionary`() {
        assertEquals(AppLanguage.CHINESE, AppLanguage.SYSTEM.resolved(Locale.SIMPLIFIED_CHINESE))
        // Only values-zh-rCN ships today, so any Chinese variant uses that dictionary.
        assertEquals(AppLanguage.CHINESE, AppLanguage.SYSTEM.resolved(Locale.TRADITIONAL_CHINESE))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.SYSTEM.resolved(Locale.FRANCE))
    }

    @Test
    fun `an explicit choice ignores the device locale`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.ENGLISH.resolved(Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLanguage.CHINESE, AppLanguage.CHINESE.resolved(Locale.US))
    }

    @Test
    fun `chinese tag matches the shipped values-zh-rCN qualifier`() {
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh-cn"))
        assertEquals("zh", AppLanguage.CHINESE.locale().language)
        assertEquals("CN", AppLanguage.CHINESE.locale().country)
    }
}
