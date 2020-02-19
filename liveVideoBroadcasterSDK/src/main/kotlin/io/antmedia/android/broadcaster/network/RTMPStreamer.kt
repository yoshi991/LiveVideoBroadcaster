package io.antmedia.android.broadcaster.network

import android.util.Log
import io.antmedia.android.BuildConfig
import net.butterflytv.rtmp_client.RTMPMuxer

class RTMPStreamer : MediaMuxer {

    private val TAG = RTMPStreamer::class.java.name

    private val muxer = RTMPMuxer()

    private var frameCount = 0
    private var lastVideoFrameTimeStamp = -1
    private var lastAudioFrameTimeStamp = -1
    private var lastReceivedVideoFrameTimeStamp = -1
    private var lastReceivedAudioFrameTimeStamp = -1
    private var lastSentFrameTimeStamp = -1
    private var isConnected = false

    private val audioFrameList = mutableListOf<StreamFrame>()
    private val videoFrameList = mutableListOf<StreamFrame>()

    private val frameSynchronized = Any()

    init {
        initialize()
    }

    override fun open(url: String): Boolean {
        if (BuildConfig.DEBUG) Log.d(TAG, "open rtmp connection")
        initialize()
        muxer.open(url, 0, 0).also {
            if (it > 0) isConnected = true
        }
        return isConnected
    }

    override fun close() {
        if (BuildConfig.DEBUG) Log.d(TAG, "close rtmp connection")
        isConnected = false
        muxer.close()
    }

    override fun isConnected(): Boolean {
        return isConnected
    }

    override fun getLastVideoFrameTimeStamp(): Int {
        return lastVideoFrameTimeStamp
    }

    override fun getLastAudioFrameTimeStamp(): Int {
        return lastAudioFrameTimeStamp
    }

    override fun writeAudio(data: ByteArray, length: Int, timestamp: Int) {
        handleMessage(MediaMuxer.Type.SEND_AUDIO, data, length, timestamp)
        synchronized(frameSynchronized) {
            frameCount++
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "writeAudio size: $length time: $timestamp")
    }

    override fun writeVideo(data: ByteArray, length: Int, timestamp: Int) {
        handleMessage(MediaMuxer.Type.SEND_VIDEO, data, length, timestamp)
        synchronized(frameSynchronized) {
            frameCount++
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "writeVideo size: $length time: $timestamp")
    }

    private fun initialize() {
        frameCount = 0
        lastVideoFrameTimeStamp = 0
        lastAudioFrameTimeStamp = 0
        lastReceivedVideoFrameTimeStamp = -1
        lastReceivedAudioFrameTimeStamp = -1
        lastSentFrameTimeStamp = -1
        isConnected = false
    }

    private fun finishFrames() {
        var videoFrameListSize = 0
        var audioFrameListSize = 0
        do {
            sendFrames()
            videoFrameListSize = videoFrameList.size
            audioFrameListSize = audioFrameList.size
            // one of the frame list should be exhausted while the other have frames
        } while (videoFrameListSize > 0 && audioFrameListSize > 0)

        if (videoFrameListSize > 0) {
            // send all video frames remained in the list
            sendVideoFrames(videoFrameList.last().timestamp)
        } else if (audioFrameListSize > 0) {
            // send all audio frames remained in the list
            sendAudioFrames(audioFrameList.last().timestamp)
        }
    }

    override fun stop() {
        handleMessage(MediaMuxer.Type.STOP_STREAMING)
    }

    override fun getFrameCountInQueue(): Int {
        synchronized(frameSynchronized) {
            return frameCount
        }
    }

    override fun getVideoFrameCountInQueue(): Int {
        synchronized(frameSynchronized) {
            return videoFrameList.size
        }
    }

    @Synchronized
    fun handleMessage(
            type: MediaMuxer.Type,
            data: ByteArray? = null,
            length: Int = -1,
            timestamp: Int = -1
    ) {
        when (type) {
            MediaMuxer.Type.SEND_AUDIO -> {
                /**
                 * data aac data,
                 * length length of the data
                 * timestamp timestamp
                 */
                data ?: return
                if (timestamp >= lastReceivedAudioFrameTimeStamp && length > 0) {
                    lastReceivedAudioFrameTimeStamp = timestamp
                    audioFrameList.add(StreamFrame(data, length, timestamp))
                } else {
                    Log.w(TAG, "discarding audio packet because time stamp is older than last packet or data lenth equal to zero")
                }
                sendFrames()
            }
            MediaMuxer.Type.SEND_VIDEO -> {
                /**
                 * data h264 nal unit,
                 * length length of the data
                 * timestamp
                 */
                data ?: return
                if (timestamp >= lastReceivedVideoFrameTimeStamp && length > 0) {
                    lastReceivedVideoFrameTimeStamp = timestamp
                    videoFrameList.add(StreamFrame(data, length, timestamp))
                } else {
                    Log.w(TAG, "discarding videp packet because time stamp is older  than last packet or data lenth equal to zero")
                }
                sendFrames()
            }
            MediaMuxer.Type.STOP_STREAMING -> {
                finishFrames()
                close()
            }
        }
    }

    /**
     * this is a simple sorting algorithm.
     * we do not know the audio or video frames timestamp in advance and they are not
     * deterministic. So we send video frames with the timestamp is less than the first one in the list
     * and the same algorithm applies for audio frames.
     */
    private fun sendFrames() {
        if (videoFrameList.size > 0) {
            sendAudioFrames(videoFrameList.first().timestamp)
        }

        if (audioFrameList.size > 0) {
            sendVideoFrames(audioFrameList.first().timestamp)
        }
    }

    @Synchronized
    private fun sendAudioFrames(timestamp: Int) {
        val iterator = audioFrameList.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()

            // if timestamp is bigger than the audio frame timestamp
            // it will be sent later so break the loop
            if (frame.timestamp > timestamp) break

            // frame time stamp should be equal or less than the previous timestamp
            // in some cases timestamp of audio and video frames may be equal
            if (frame.timestamp >= lastSentFrameTimeStamp) {
                if (frame.timestamp == lastSentFrameTimeStamp) {
                    frame.timestamp++
                }

                if (isConnected) {
                    val result = muxer.writeAudio(frame.data, 0, frame.length, frame.timestamp.toLong())

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "send audio result: $result, time: ${frame.timestamp}, length: ${frame.length}")
                    }

                    if (result < 0) {
                        close()
                    }
                }
                lastAudioFrameTimeStamp = frame.timestamp
                lastSentFrameTimeStamp = frame.timestamp
                synchronized(frameSynchronized) {
                    frameCount--
                }
            }
            iterator.remove()
        }
    }

    @Synchronized
    private fun sendVideoFrames(timestamp: Int) {
        val iterator = videoFrameList.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            // if frame timestamp is not smaller than the timestamp
            // break the loop, it will be sent later
            if (frame.timestamp > timestamp) break

            // frame time stamp should be equal or less than timestamp
            // in some cases timestamp of audio and video frames may be equal
            if (frame.timestamp >= lastSentFrameTimeStamp) {
                if (frame.timestamp == lastSentFrameTimeStamp) {
                    frame.timestamp++
                }

                if (isConnected) {
                    val result = muxer.writeVideo(frame.data, 0, frame.length, frame.timestamp.toLong())

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "send video result: $result, time: ${frame.timestamp}, length: ${frame.length}")
                    }

                    if (result < 0) {
                        close()
                    }
                }
                lastVideoFrameTimeStamp = frame.timestamp
                lastSentFrameTimeStamp = frame.timestamp
                synchronized(frameSynchronized) {
                    frameCount--
                }
            }
            iterator.remove()
        }
    }
}