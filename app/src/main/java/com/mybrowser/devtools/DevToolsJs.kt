package com.mybrowser.devtools

import org.json.JSONObject
import org.json.JSONTokener

/** Shared formatting for the tiny diagnostics-only JS bridge used by the DOM and storage readers. */
internal object DevToolsJs {
    const val UNAVAILABLE = "__pureDevToolsUnavailable__"

    fun decode(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty() || value == "null") return null
        return runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
            ?: value.removeSurrounding("\"")
    }

    /**
     * Calls into the page-side accessor for the current document.
     *
     * This is native-to-page only: the accessor exposes DOM reads and storage reads, never a
     * native capability. The page cannot reach Android through this expression.
     */
    fun call(namespace: String, body: String): String {
        val quoted = JSONObject.quote(namespace)
        return "(function(){try{" +
            "var a=window[$quoted];if(!a||!a.__pureDevTools)return \"$UNAVAILABLE\";" +
            "var r=(function(a){$body})(a);" +
            "return typeof r==='string'?r:JSON.stringify(r);" +
            "}catch(e){return JSON.stringify({error:String((e&&e.message)||e)})}})()"
    }

    fun callFunction(namespace: String, name: String, vararg args: String): String {
        val quoted = JSONObject.quote(namespace)
        val arguments = args.joinToString(",")
        return "(function(){try{" +
            "var a=window[$quoted];if(!a||!a.__pureDevTools)return \"$UNAVAILABLE\";" +
            "var r=a.$name($arguments);" +
            "return typeof r==='string'?r:JSON.stringify(r);" +
            "}catch(e){return JSON.stringify({error:String((e&&e.message)||e)})}})()"
    }
}

internal sealed interface PageResult<out T> {
    data class Ok<T>(val value: T) : PageResult<T>
    data object Unavailable : PageResult<Nothing>
    data object Invalid : PageResult<Nothing>
    data class Failed(val message: String?) : PageResult<Nothing>
}
