package com.mybrowser.privacy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Main-thread, process-owned cleanup queue. Failure keeps navigation closed until retry succeeds. */
class PrivacyCleanupBarrier(private val scope: CoroutineScope) {
    enum class State { READY, RUNNING, FAILED }
    private val mutable = MutableStateFlow(State.READY)
    val state = mutable.asStateFlow()
    private val work = ArrayDeque<suspend () -> Unit>()

    fun enqueue(action: suspend () -> Unit) {
        work.addLast(action)
        if (mutable.value == State.READY) retry()
    }

    fun retry() {
        if (mutable.value == State.RUNNING || work.isEmpty()) return
        mutable.value = State.RUNNING
        scope.launch {
            try {
                while (work.isNotEmpty()) {
                    work.first().invoke()
                    work.removeFirst()
                }
                mutable.value = State.READY
            } catch (cancelled: CancellationException) {
                mutable.value = State.FAILED
                throw cancelled
            } catch (_: Exception) {
                mutable.value = State.FAILED
            }
        }
    }

    suspend fun awaitReady() { state.first { it == State.READY } }
}
