package app.cmuxpocket

import app.cmuxpocket.ui.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `default language is english`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.DEFAULT)
    }

    @Test
    fun `unknown or missing tag falls back to english`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("fr"))
    }

    @Test
    fun `persisted tags round trip`() {
        AppLanguage.values().forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }

    @Test
    fun `chinese tag matches the shipped values-zh-rCN qualifier`() {
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh-cn"))
        assertEquals("zh", AppLanguage.CHINESE.locale().language)
        assertEquals("CN", AppLanguage.CHINESE.locale().country)
    }
}
