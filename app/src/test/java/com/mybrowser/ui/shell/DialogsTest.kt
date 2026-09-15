package com.mybrowser.ui.shell

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DialogsTest {
    @Test fun lifecycleDismissalAnswersExactlyOnce() {
        val dialogs = Dialogs()
        val replies = mutableListOf<Boolean>()
        dialogs.confirm(RuntimeEnvironment.getApplication(), "Title", "Body", onResult = replies::add)
        dialogs.dismiss()
        dialogs.dismiss()
        assertEquals(listOf(false), replies)
    }

    @Test fun overlappingRequestsCancelTheNewOneWithoutLeakingEitherCallback() {
        val dialogs = Dialogs()
        val context = RuntimeEnvironment.getApplication()
        val replies = mutableListOf<String>()
        dialogs.alert(context, "First", "Message") { replies += "first" }
        dialogs.prompt(context, "Second", "Message", "default") { replies += if (it == null) "cancelled" else it }
        assertEquals(listOf("cancelled"), replies)
        dialogs.dismiss()
        assertEquals(listOf("cancelled", "first"), replies)
    }
}
