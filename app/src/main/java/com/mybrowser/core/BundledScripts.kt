package com.mybrowser.core

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** APK scripts are immutable and shared by every tab; no Activity is retained. */
internal object BundledScripts {
    private val sources = ConcurrentHashMap<String, String>()

    fun read(context: Context, name: String): String = sources.computeIfAbsent(name) {
        context.assets.open(it).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
    }
}
