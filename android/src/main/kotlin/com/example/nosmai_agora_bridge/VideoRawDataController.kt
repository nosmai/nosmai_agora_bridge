package com.example.nosmai_agora_bridge

import android.content.Context
import android.util.Log
import com.nosmai.effect.api.NosmaiSDK
import io.agora.base.JavaI420Buffer
import io.agora.base.TextureBufferHelper
import io.agora.base.VideoFrame
import io.agora.base.internal.video.EglBase
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.gl.EglBaseProvider
import io.agora.rtc2.video.VideoEncoderConfiguration
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoRawDataController(context: Context, myAppId: String) {
    companion object {
        private const val TAG = "NosmaiAgoraBridge"
        private const val STREAM_WIDTH = 720
        private const val STREAM_HEIGHT = 1280
        private const val STREAM_BITRATE_KBPS = 2500
        private const val STREAM_MIN_BITRATE_KBPS = 1200
    }

    private var rtcEngine: RtcEngine? = null
    private var textureHelper: TextureBufferHelper? = null
    private var textureBridge: AgoraTextureBridge? = null
    private var pixelExecutor: ExecutorService? = null
    private var textureMode = false
    private var inChannel = false
    private var streamingRequested = false
    private var frameCount = 0

    private val rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            super.onJoinChannelSuccess(channel, uid, elapsed)
            inChannel = true
            frameCount = 0
            Log.i(TAG, "Agora joined channel=$channel uid=$uid textureMode=$textureMode")

            if (streamingRequested && textureMode) {
                armTextureCallback()
            }
        }

        override fun onLeaveChannel(stats: RtcStats?) {
            super.onLeaveChannel(stats)
            inChannel = false
            clearStreamCallbacks()
            Log.i(TAG, "Agora left channel")
        }

        override fun onError(err: Int) {
            super.onError(err)
            Log.e(TAG, "Agora error=$err")
        }
    }

    init {
        rtcEngine = RtcEngine.create(RtcEngineConfig().apply {
            mAppId = myAppId
            mContext = context.applicationContext
            mEventHandler = rtcEventHandler
        })
        pixelExecutor = Executors.newSingleThreadExecutor()

        rtcEngine?.enableVideo()
        rtcEngine?.setLocalVideoMirrorMode(Constants.VIDEO_MIRROR_MODE_DISABLED)
        setupTextureMode()
        configureEncoder()
    }

    fun nativeHandle(): Long = rtcEngine?.nativeHandle ?: 0L

    fun startStreaming(token: String?, channelName: String, uid: Int): Boolean {
        val engine = rtcEngine ?: return false
        if (channelName.isBlank()) return false

        streamingRequested = true
        frameCount = 0
        clearStreamCallbacks()
        NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.DUAL_OUTPUT)

        engine.setExternalVideoSource(
            true,
            textureMode,
            Constants.ExternalVideoSourceType.VIDEO_FRAME
        )
        engine.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)

        if (textureMode) {
            Log.i(TAG, "Streaming via zero-readback texture path; callback arms after join")
        } else {
            Log.w(TAG, "Texture mode unavailable; streaming via CPU I420 fallback")
            armPixelCallback()
        }

        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            publishCameraTrack = false
            publishCustomVideoTrack = true
            publishCustomAudioTrack = true
            autoSubscribeVideo = true
            autoSubscribeAudio = true
        }

        val safeToken = token?.takeIf { it.isNotBlank() }
        val result = engine.joinChannel(safeToken, channelName, uid, options)
        if (result != 0) {
            streamingRequested = false
            clearStreamCallbacks()
            NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.PREVIEW_ONLY)
            Log.e(TAG, "joinChannel failed result=$result")
            return false
        }
        return true
    }

    fun stopStreaming(): Boolean {
        streamingRequested = false
        clearStreamCallbacks()
        NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.PREVIEW_ONLY)
        val result = rtcEngine?.leaveChannel() ?: 0
        inChannel = false
        return result == 0
    }

    fun switchCamera() {
        // Camera ownership belongs to NosmaiCameraPreview in texture mode.
    }

    fun dispose() {
        try {
            stopStreaming()
        } catch (_: Throwable) {
        }

        try {
            textureBridge?.release()
        } catch (_: Throwable) {
        }
        textureBridge = null

        try {
            textureHelper?.dispose()
        } catch (_: Throwable) {
        }
        textureHelper = null
        textureMode = false

        pixelExecutor?.shutdown()
        pixelExecutor = null

        rtcEngine = null
        RtcEngine.destroy()
    }

    private fun setupTextureMode() {
        val engine = rtcEngine ?: return
        try {
            try {
                System.loadLibrary("nosmai")
                Log.i(TAG, "libnosmai loaded before Agora share-context registration")
            } catch (t: Throwable) {
                textureMode = false
                textureHelper = null
                textureBridge = null
                Log.w(TAG, "Early libnosmai load failed; CPU fallback will be used", t)
                return
            }

            val agoraContext: EglBase.Context =
                EglBaseProvider.instance().getRootEglBase().getEglBaseContext()
            val nativeHandle = agoraContext.getNativeEglContext()
            NosmaiSDK.setAgoraShareContext(nativeHandle)

            val helper = TextureBufferHelper.create("nosmai-flutter-agora", agoraContext)
            textureHelper = helper
            textureMode = helper != null && nativeHandle != 0L
            if (textureMode) {
                textureBridge = AgoraTextureBridge(helper!!, engine)
            }

            Log.i(
                TAG,
                "Texture streaming setup: shareCtx=$nativeHandle helper=${textureHelper != null} textureMode=$textureMode"
            )
        } catch (t: Throwable) {
            textureMode = false
            textureHelper = null
            textureBridge = null
            Log.w(TAG, "Texture streaming setup failed; CPU fallback will be used", t)
        }
    }

    private fun configureEncoder() {
        val config = VideoEncoderConfiguration(
            VideoEncoderConfiguration.VideoDimensions(STREAM_WIDTH, STREAM_HEIGHT),
            VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30,
            STREAM_BITRATE_KBPS,
            VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
        )
        config.minBitrate = STREAM_MIN_BITRATE_KBPS
        config.mirrorMode = VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
        rtcEngine?.setVideoEncoderConfiguration(config)
        Log.i(
            TAG,
            "Agora encoder: ${STREAM_WIDTH}x$STREAM_HEIGHT ${STREAM_BITRATE_KBPS}kbps min=$STREAM_MIN_BITRATE_KBPS"
        )
    }

    private fun armTextureCallback() {
        NosmaiSDK.setTextureFrameCallback { texId, width, height, timestampNs, fence ->
            val bridge = textureBridge
            if (!inChannel || bridge == null || !textureMode) {
                NosmaiSDK.releaseStreamSlot(texId)
                return@setTextureFrameCallback
            }
            bridge.pushCopy(texId, width, height, timestampNs) {
                NosmaiSDK.releaseStreamSlot(texId)
            }
            frameCount++
            if (frameCount % 90 == 0) {
                Log.i(TAG, "Pushed texture frames=$frameCount size=${width}x$height")
            }
        }
    }

    private fun armPixelCallback() {
        NosmaiSDK.setFrameCallback { frame ->
            if (!inChannel || frame?.pixelBuffer == null) return@setFrameCallback
            pushPixelFrame(
                frame.pixelBuffer,
                frame.width,
                frame.height,
                frame.timestampNs,
                frame.format,
                0
            )
        }
    }

    private fun clearStreamCallbacks() {
        try {
            NosmaiSDK.setTextureFrameCallback(null)
        } catch (_: Throwable) {
        }
        try {
            NosmaiSDK.setFrameCallback(null)
        } catch (_: Throwable) {
        }
    }

    private fun pushPixelFrame(
        frameData: ByteArray,
        width: Int,
        height: Int,
        timestampNs: Long,
        format: Int,
        rotation: Int
    ) {
        val engine = rtcEngine ?: return
        val executor = pixelExecutor ?: return
        if (!inChannel || format != 1) return

        executor.execute {
            try {
                val ySize = width * height
                val uvWidth = (width + 1) / 2
                val uvHeight = (height + 1) / 2
                val uSize = uvWidth * uvHeight
                val vSize = uSize
                val expectedSize = ySize + uSize + vSize
                if (frameData.size < expectedSize) return@execute

                val dataY = ByteBuffer.allocateDirect(ySize)
                val dataU = ByteBuffer.allocateDirect(uSize)
                val dataV = ByteBuffer.allocateDirect(vSize)
                dataY.put(frameData, 0, ySize)
                dataU.put(frameData, ySize, uSize)
                dataV.put(frameData, ySize + uSize, vSize)
                dataY.rewind()
                dataU.rewind()
                dataV.rewind()

                val buffer = JavaI420Buffer.wrap(
                    width,
                    height,
                    dataY,
                    width,
                    dataU,
                    uvWidth,
                    dataV,
                    uvWidth,
                    null
                )
                val videoFrame = VideoFrame(buffer, rotation, timestampNs)
                engine.pushExternalVideoFrame(videoFrame)
                videoFrame.release()
                frameCount++
                if (frameCount % 90 == 0) {
                    Log.i(TAG, "Pushed I420 frames=$frameCount size=${width}x$height")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pushPixelFrame failed", t)
            }
        }
    }
}
