package io.antmedia.android.broadcaster.network

data class StreamFrame(
        var data: ByteArray,
        var length: Int,
        var timestamp: Int
)