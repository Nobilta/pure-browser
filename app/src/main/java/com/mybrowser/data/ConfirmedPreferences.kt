package com.mybrowser.data

import android.content.SharedPreferences
import java.io.IOException

/**
 * One atomic preference-file edit, with a checked disk result. Call off the UI thread.
 * Android updates its in-memory map even when commit fails: restore only the touched
 * keys before reporting failure, leaving unrelated preferences alone. A second disk
 * failure is attached to the error; it must never be turned into a successful import.
 */
@android.annotation.SuppressLint("UseKtx") // KTX edit discards the commit result required here.
internal fun SharedPreferences.commitConfirmed(values: Map<String, Any?>) {
    if (values.isEmpty()) return
    synchronized(this) {
        val previous = all.let { snapshot -> values.keys.associateWith { snapshot[it] } }
        try {
            if (!edit().putValues(values).commit()) throw IOException("Unable to save preferences")
        } catch (error: Exception) {
            try {
                if (!edit().putValues(previous).commit()) throw IOException("Unable to restore preferences")
            } catch (restoreError: Exception) {
                error.addSuppressed(restoreError)
            }
            throw error
        }
    }
}

internal fun SharedPreferences.Editor.putValues(values: Map<String, Any?>): SharedPreferences.Editor = apply {
    values.forEach { (key, value) ->
        when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            else -> error("Unsupported preference type for $key")
        }
    }
}
