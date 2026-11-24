package io.github.mangi.eta.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrefsDefaultsTest {
    @Test
    fun defaultsMatchRecommendedInitialSettings() {
        assertEquals(
            mapOf(
                Prefs.Keys.POWER_KEY_TAKEOVER to false,
                Prefs.Keys.ASSISTANT_AUTO_CONFIG to false,
                Prefs.Keys.HOTWORD_SELF_HEAL to false,
                Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH to true,
                Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
                Prefs.Keys.LOCKSCREEN_VOICE_COMMAND to false,
                Prefs.Keys.SCREEN_ON_VOICE_COMMAND to false,
                Prefs.Keys.AGENT_CUSTOM_MODEL to true,
                Prefs.Keys.AGENT_REQUIRE_PREFIX to false,
                Prefs.Keys.AGENT_BORROW_BREENO to false,
                Prefs.Keys.VIVO_CUSTOM_MODEL to true,
                Prefs.Keys.VIVO_REQUIRE_PREFIX to true,
                Prefs.Keys.AGENT_TERMINAL_TOOLS to true,
                Prefs.Keys.AGENT_BROWSER_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
                Prefs.Keys.AGENT_THINKING_ENABLED to true,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED to true,
                Prefs.Keys.AGENT_SUBAGENT_ENABLED to true,
            ),
            Prefs.Keys.BOOLEAN_DEFAULTS,
        )
        assertFalse(
            Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET),
        )
    }

    @Test
    fun autoCompactionThresholdSnapsToCurrentChoices() {
        // 档位改版后旧值必须吸附到最近的合法档位，不能让 UI 下拉选不中、也不能静默变成关闭。
        assertEquals(listOf(0, 20, 30, 40, 50, 80, 90), Prefs.Keys.AUTO_COMPACTION_TRIGGER_PERCENT_CHOICES)
        assertEquals(80, Prefs.Keys.AUTO_COMPACTION_TRIGGER_PERCENT_DEFAULT)
        assertEquals(90, Prefs.Keys.AUTO_COMPACTION_TRIGGER_PERCENT_MAX)
        assertEquals(0, Prefs.normalizeAutoCompactionPercent(0))
        assertEquals(0, Prefs.normalizeAutoCompactionPercent(-5))
        assertEquals(20, Prefs.normalizeAutoCompactionPercent(5))
        assertEquals(20, Prefs.normalizeAutoCompactionPercent(20))
        assertEquals(20, Prefs.normalizeAutoCompactionPercent(25))
        assertEquals(40, Prefs.normalizeAutoCompactionPercent(40))
        // 旧档位 60/70/85/95 吸附到新档位，不落到 0。
        assertEquals(50, Prefs.normalizeAutoCompactionPercent(60))
        assertEquals(80, Prefs.normalizeAutoCompactionPercent(70))
        assertEquals(80, Prefs.normalizeAutoCompactionPercent(85))
        assertEquals(90, Prefs.normalizeAutoCompactionPercent(95))
        assertEquals(90, Prefs.normalizeAutoCompactionPercent(100))
    }

    @Test
    fun localAgentKeysMatchRuntimeOwnedSettings() {
        assertEquals(
            setOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS,
                Prefs.Keys.AGENT_BROWSER_TOOLS,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                Prefs.Keys.AGENT_THINKING_ENABLED,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                Prefs.Keys.AGENT_SUBAGENT_ENABLED,
            ),
            Prefs.Keys.LOCAL_AGENT_KEYS,
        )
    }
}
