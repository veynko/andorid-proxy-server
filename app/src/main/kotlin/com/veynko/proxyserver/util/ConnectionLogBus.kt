package com.veynko.proxyserver.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ConnectionLogBus {

    private const val DEFAULT_PORT = 0

    private val _events = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<String> = _events.asSharedFlow()

    fun log(host: String, port: Int = DEFAULT_PORT) {
        val entry = if (port == DEFAULT_PORT) host else "$host:$port"
        _events.tryEmit(entry)
    }
}
