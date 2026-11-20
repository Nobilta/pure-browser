package com.mybrowser.ui.components

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h720dp-mdpi")
class BrowserTextFieldTest {
    @Test fun longInvalidInputKeepsItsErrorVisibleInsideTheHeightLimit() {
        render("invalid script\n".repeat(40), "Invalid script") { nodes ->
            val input = nodes.single { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            val error = nodes.first { it.config.getOrNull(SemanticsProperties.TestTag) == "browser_text_field_error" }
            assertEquals(null, error.config.getOrNull(SemanticsProperties.Text))
            assertEquals("Invalid script", input.config.getOrNull(SemanticsProperties.Error))
            assertTrue(input.boundsInRoot.height >= 48f)
            assertTrue(error.boundsInRoot.height > 0f)
            assertTrue(error.boundsInRoot.top >= input.boundsInRoot.bottom)
            assertTrue(error.boundsInRoot.bottom <= 300f)
        }
    }

    @Test fun anInputInAScrollingDialogKeepsItsNaturalHeight() {
        render("value", null) { nodes ->
            val input = nodes.single { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            assertTrue(input.boundsInRoot.height >= 48f)
            assertTrue(input.boundsInRoot.bottom < 100f)
        }
    }

    private fun render(value: String, error: String?, check: (List<SemanticsNode>) -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = ComposeView(activity.get())
        try {
            view.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        BrowserTextField(value, "Script", {}, singleLine = false, errorMessage = error,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp))
                    }
                }
            }
            activity.get().setContentView(view)
            shadowOf(Looper.getMainLooper()).idle()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 720)
            val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            fun children(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::children)
            check(children(root))
        } finally {
            view.disposeComposition()
            activity.pause().stop().destroy()
        }
    }
}
