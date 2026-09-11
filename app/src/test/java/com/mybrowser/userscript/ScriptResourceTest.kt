package com.mybrowser.userscript

import android.app.Application
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScriptResourceTest {
    @Test fun resourcePreservesBinaryAndChineseTextWithBoundedMimeAndBytes() {
        val text = "文章与代码 { color: red; }"
        val resource = ScriptResource("text/css", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
        assertEquals(text, ScriptResource.decode(resource.json()).runtimeJson().getString("text"))
        val binary = byteArrayOf(0, -1, -128, 32)
        val image = ScriptResource("image/png", Base64.encodeToString(binary, Base64.NO_WRAP))
        assertArrayEquals(binary, ScriptResource.decode(image.json()).bytes())
        assertTrue(image.runtimeJson().getString("url").startsWith("data:image/png;base64,"))
        assertThrows(IllegalArgumentException::class.java) { ScriptResource.decode(resource.json().put("mime", "text/html\r\nx:1")) }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptResource.decode(JSONObject().put("mime", "text/plain").put("base64", "a".repeat(ScriptResource.MAX_BYTES * 2)))
        }
    }
}
