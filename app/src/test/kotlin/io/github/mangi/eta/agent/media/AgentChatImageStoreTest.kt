package io.github.mangi.eta.agent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 用例：覆盖图片落盘仓库的引用判定与 mime 映射。
 * 注意：本机 arm64 上 Robolectric 用例一律被 AssumptionViolatedException 跳过，
 * 所以这里不碰 Context / android.util.Base64，只测纯函数（skipped 不算覆盖）。
 */
class AgentChatImageStoreTest {

    @Test
    fun pathReferencesAreRecognized() {
        assertTrue(AgentChatImageStore.isPathReference("/storage/emulated/0/Android/data/x/files/chat-images/img-abc.jpg"))
        assertTrue(AgentChatImageStore.isPathReference("file:///data/user/0/io.github.mangi.eta/files/chat-images/img-abc.png"))
        assertTrue(AgentChatImageStore.isPathReference(" /data/local/tmp/eta/img.webp "))
    }

    @Test
    fun nonPathReferencesAreNotTreatedAsPaths() {
        assertFalse(AgentChatImageStore.isPathReference(""))
        assertFalse(AgentChatImageStore.isPathReference("   "))
        assertFalse(AgentChatImageStore.isPathReference("data:image/jpeg;base64,AAAA"))
        assertFalse(AgentChatImageStore.isPathReference("https://example.com/image.png"))
        assertFalse(AgentChatImageStore.isPathReference("http://example.com/image.png"))
        assertFalse(AgentChatImageStore.isPathReference("content://media/external/images/1"))
    }

    @Test
    fun mimeToExtensionMapping() {
        assertEquals("png", AgentChatImageStore.extensionForMime("image/png"))
        assertEquals("webp", AgentChatImageStore.extensionForMime("IMAGE/WEBP"))
        assertEquals("gif", AgentChatImageStore.extensionForMime("image/gif"))
        assertEquals("jpg", AgentChatImageStore.extensionForMime("image/jpeg"))
        assertEquals("jpg", AgentChatImageStore.extensionForMime("image/anything-unknown"))
        assertEquals("jpg", AgentChatImageStore.extensionForMime(""))
    }

    @Test
    fun extensionToMimeRoundTrip() {
        assertEquals("image/png", AgentChatImageStore.mimeForReference("img-abc.png"))
        assertEquals("image/webp", AgentChatImageStore.mimeForReference("/tmp/img.webp"))
        assertEquals("image/gif", AgentChatImageStore.mimeForReference("x/y/z.gif"))
        assertEquals("image/jpeg", AgentChatImageStore.mimeForReference("img-abc.jpg"))
        assertEquals("image/jpeg", AgentChatImageStore.mimeForReference("img-abc.unknown"))
        assertEquals("image/jpeg", AgentChatImageStore.mimeForReference("no-extension"))
    }
}
