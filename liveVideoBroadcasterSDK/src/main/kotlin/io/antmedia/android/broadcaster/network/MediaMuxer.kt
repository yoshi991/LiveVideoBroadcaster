package io.antmedia.android.broadcaster.network

interface MediaMuxer {

    enum class Type {
        SEND_AUDIO,
        SEND_VIDEO,
        STOP_STREAMING
    }

    /**
     * @param url [RTMP Base URL]/[Application Key]]/[Stream Key]
     */
    fun open(url: String): Boolean

    fun close()

    /**
     * @param data aac data
     * @param length
     * @param timestamp
     */
    fun writeAudio(data: ByteArray, length: Int, timestamp: Int)

    /**
     * @param h264 nal unit
     * @param length
     * @param timestamp
     */
    fun writeVideo(data: ByteArray, length: Int, timestamp: Int)

    fun stop()

    fun isConnected(): Boolean

    fun getFrameCountInQueue(): Int

    fun getVideoFrameCountInQueue(): Int

    /**
     * @return the last audio frame timestamp in milliseconds
     */
    fun getLastAudioFrameTimeStamp(): Int

    /**
     * @return the last video frame timestamp in milliseconds
     */
    fun getLastVideoFrameTimeStamp(): Int
}