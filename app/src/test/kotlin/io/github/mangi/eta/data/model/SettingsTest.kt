package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun memoryIsEnabledByDefault() {
        assertTrue(Settings().memoryEnabled)
    }

    @Test
    fun keepaliveIsOffByDefault() {
        // 常驻会挂一条通知并持有 WifiLock，属于用户主动选择的能力，不能默认打开。
        assertFalse(Settings().keepaliveEnabled)
    }

    @Test
    fun appearanceUsesBackwardCompatibleDefaults() {
        assertEquals(AppearanceSettings(), Settings().appearance)
    }
}
