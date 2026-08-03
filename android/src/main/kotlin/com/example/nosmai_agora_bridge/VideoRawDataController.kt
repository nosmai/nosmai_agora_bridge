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
            // Only disarm if the app actually asked to stop. A leave callback can
            // arrive while a stream is still wanted (e.g. a rejected duplicate
            // join leaves the channel it never really held); clearing callbacks
            // unconditionally there would silence the producer for the stream
            // that IS running.
            if (!streamingRequested) {
                clearStreamCallbacks()
            }
            Log.i(TAG, "Agora left channel (streamingRequested=$streamingRequested)")
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
        // Register the Nosmai↔Agora EGL share context ONCE, up-front. This MUST
        // happen before the Nosmai GL context is created (else the share group is
        // wrong → black remote), and it is a one-time SDK registration — never
        // un-set per stream. Only the TextureBufferHelper (the GL thread that
        // contends the shared context) is created/destroyed per stream. See
        // registerShareContext() / createTextureHelper() / releaseTextureHelper().
        registerShareContext()
        configureEncoder()
    }

    fun nativeHandle(): Long = rtcEngine?.nativeHandle ?: 0L

    fun startStreaming(token: String?, channelName: String, uid: Int): Boolean {
        val engine = rtcEngine ?: return false
        if (channelName.isBlank()) return false

        // RE-ENTRANCY GUARD. The Dart caller is driven by a socket event
        // ("stream-started") which the server can emit more than once, so this
        // could be entered twice milliseconds apart. Without the guard the second
        // call re-ran the whole setup while the first joinChannel was still in
        // flight; Agora rejected it with -17 (ERR_JOIN_CHANNEL_REJECTED) and the
        // failure path below then cleared the texture callback and reset the
        // render mode to PREVIEW_ONLY — tearing down the FIRST attempt, which was
        // about to succeed. The channel joined, but the frame producer was off:
        // zero frames pushed, black remote, no error and no frozen frame.
        if (streamingRequested) {
            Log.w(TAG, "startStreaming ignored: already streaming/joining channel=$channelName")
            return true
        }

        streamingRequested = true
        frameCount = 0
        clearStreamCallbacks()
        // Create the TextureBufferHelper GL thread FRESH for this stream. Doing it
        // per-stream (instead of once at init) is the fix for the 2nd-go-live
        // freeze→black: previously the helper GL thread + its Nosmai EGL share-group
        // binding persisted across stopStreaming and kept contending the single
        // Nosmai GL worker, so on the next go-live the platform-thread surface-
        // release SyncRunWithContext head-of-line-blocked ~2.8s behind the saturated,
        // share-context-contended worker → frozen frame → permanent black.
        createTextureHelper()
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
        // Disarm the external video source so a reused engine (the app keeps the
        // engine alive across streams) re-arms cleanly on the next startStreaming
        // instead of inheriting a stale external-source registration.
        try {
            rtcEngine?.setExternalVideoSource(
                false,
                textureMode,
                Constants.ExternalVideoSourceType.VIDEO_FRAME
            )
        } catch (t: Throwable) {
            Log.w(TAG, "stopStreaming: disarm external source failed: ${t.message}")
        }
        val result = rtcEngine?.leaveChannel() ?: 0
        inChannel = false
        // Tear down the TextureBufferHelper GL thread + its Nosmai share-group
        // binding NOW (not at dispose). This is the core of the 2nd-go-live freeze
        // fix: the contending helper GL thread must NOT survive across the
        // go-live/end-live cycle. The Nosmai render mode is already back to
        // PREVIEW_ONLY (above), so after this the Nosmai GL worker runs uncontended
        // and the next createSinkPreviewTexture's platform-thread Sync drains fast.
        // The share-context REGISTRATION stays (one-time); only the helper thread
        // goes. A fresh helper is created in the next startStreaming.
        releaseTextureHelper()
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

        // stopStreaming already tore down the helper (releaseTextureHelper); call
        // again defensively in case dispose is reached without a prior stream.
        releaseTextureHelper()
        textureMode = false

        pixelExecutor?.shutdown()
        pixelExecutor = null

        rtcEngine = null
        RtcEngine.destroy()
    }

    // Whether the one-time Nosmai↔Agora EGL share-context registration succeeded.
    // The share context is a one-time SDK registration (must precede the Nosmai GL
    // context); the TextureBufferHelper GL thread is created/destroyed per stream.
    private var shareContextReady = false

    /**
     * ONE-TIME: load libnosmai and register the Agora EGL context as the Nosmai
     * share context. Must run before the Nosmai GL context is created. Does NOT
     * create the TextureBufferHelper (that is per-stream — see createTextureHelper).
     */
    private fun registerShareContext() {
        try {
            try {
                System.loadLibrary("nosmai")
                Log.i(TAG, "libnosmai loaded before Agora share-context registration")
            } catch (t: Throwable) {
                shareContextReady = false
                Log.w(TAG, "Early libnosmai load failed; CPU fallback will be used", t)
                return
            }

            val agoraContext: EglBase.Context =
                EglBaseProvider.instance().getRootEglBase().getEglBaseContext()
            val nativeHandle = agoraContext.getNativeEglContext()
            NosmaiSDK.setAgoraShareContext(nativeHandle)
            shareContextReady = nativeHandle != 0L
            Log.i(TAG, "Share-context registered: shareCtx=$nativeHandle ready=$shareContextReady")
        } catch (t: Throwable) {
            shareContextReady = false
            Log.w(TAG, "Share-context registration failed; CPU fallback will be used", t)
        }
    }

    /**
     * PER-STREAM: create the TextureBufferHelper GL thread + the texture bridge.
     * Idempotent (reuses an existing helper if somehow still alive). Called from
     * startStreaming so the contending helper thread only exists while streaming.
     */
    private fun createTextureHelper() {
        val engine = rtcEngine ?: return
        if (!shareContextReady) {
            textureMode = false
            Log.w(TAG, "createTextureHelper: share context not ready; CPU fallback")
            return
        }
        if (textureHelper != null) {
            // Already have one (e.g. defensive double-call) — keep it.
            textureMode = textureBridge != null
            return
        }
        try {
            val agoraContext: EglBase.Context =
                EglBaseProvider.instance().getRootEglBase().getEglBaseContext()
            val helper = TextureBufferHelper.create("nosmai-flutter-agora", agoraContext)
            textureHelper = helper
            textureMode = helper != null
            if (textureMode) {
                textureBridge = AgoraTextureBridge(helper!!, engine)
            }
            Log.i(TAG, "createTextureHelper: helper=${textureHelper != null} textureMode=$textureMode")
        } catch (t: Throwable) {
            textureMode = false
            textureHelper = null
            textureBridge = null
            Log.w(TAG, "createTextureHelper failed; CPU fallback will be used", t)
        }
    }

    /**
     * PER-STREAM: tear down the TextureBufferHelper GL thread + bridge so it stops
     * contending the Nosmai GL worker via the shared EGL context. Safe to call when
     * already torn down. The share-context registration is NOT touched.
     */
    private fun releaseTextureHelper() {
        try { textureBridge?.release() } catch (_: Throwable) {}
        textureBridge = null
        try { textureHelper?.dispose() } catch (_: Throwable) {}
        textureHelper = null
        Log.i(TAG, "releaseTextureHelper: helper GL thread torn down (share-context kept)")
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
