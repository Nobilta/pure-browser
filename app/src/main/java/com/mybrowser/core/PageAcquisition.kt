package com.mybrowser.core

/** Allocation and configuration share one recovery boundary; failed instances never enter use. */
internal fun <T : Any> acquireWithMemoryRecovery(
    create: () -> T,
    configure: (T) -> Unit,
    discard: (T) -> Unit,
    evict: () -> Boolean,
): T {
    while (true) {
        var instance: T? = null
        try {
            instance = create()
            configure(instance)
            return instance
        } catch (error: Throwable) {
            instance?.let(discard)
            if (error !is OutOfMemoryError || !evict()) throw error
        }
    }
}
