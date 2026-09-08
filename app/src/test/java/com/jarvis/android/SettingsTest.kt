package com.jarvis.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Settings.isValid] is not covered here because it goes through android.net.Uri,
 * which is stubbed out in local unit tests; normalization is the part with real
 * behaviour anyway, since origin comparison in MainActivity depends on it.
 */
class SettingsTest {

    @Test
    fun `trailing slashes are trimmed`() {
        assertEquals("https://jarvis.example.com", Settings.normalize("https://jarvis.example.com/"))
        assertEquals("https://jarvis.example.com", Settings.normalize("https://jarvis.example.com///"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://jarvis.example.com", Settings.normalize("  https://jarvis.example.com  "))
    }

    @Test
    fun `a path is preserved`() {
        assertEquals("https://example.com/jarvis", Settings.normalize("https://example.com/jarvis/"))
    }
}
