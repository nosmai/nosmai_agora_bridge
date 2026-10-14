package com.example.nosmai_agora_bridge

import android.content.Context
import android.util.Log
import com.nosmai.effect.api.NosmaiSDK
import io.agora.base.JavaI420Buffer
import io.agora.base.TextureBufferHelper
import io.agora.base.VideoFrame
import io.agora.rtc2.*
import io.agora.rtc2.gl.EglBaseProvider
import io.agora.rtc2.video.VideoEncoderConfiguration
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Commands are serialized by the plugin worker; RTC and frame callbacks use session identity. */
class VideoRawDataController(context: Context, appId: String) {
    private class Pixels(val width: Int, val height: Int) {
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val y = ByteBuffer.allocateDirect(width * height)
        val u = ByteBuffer.allocateDirect(uvWidth * uvHeight)
        val v = ByteBuffer.allocateDirect(uvWidth * uvHeight)
    }
    private class Session(val channel: String, val uid: Int, val bridge: AgoraTextureBridge?) {
        val active = AtomicBoolean(true)
        val joined = AtomicBoolean(false)
        val pixelPending = AtomicBoolean(false)
        val pixelCount = AtomicInteger()
        val freePixels = ConcurrentLinkedQueue<Pixels>()
    }

    @Volatile private var current: Session? = null
    @Volatile private var leaving = false
    private var retiring: Session? = null
    private var drain: CompletableFuture<Void>? = null
    private var shareHandle = 0L
    private val pixels = Executors.newSingleThreadExecutor { Thread(it, "nosmai-agora-pixels") }
    private var engine: RtcEngine? = null
    private val events = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            current?.let { if (it.active.get() && it.channel == channel) it.joined.set(true) }
        }
        override fun onLeaveChannel(stats: RtcStats?) {
            // A delayed leave from a previous session must not clear new callbacks.
            leaving = false
        }
        override fun onError(err: Int) { Log.e("NosmaiAgoraBridge", "Agora error=$err") }
    }

    init {
        engine = RtcEngine.create(RtcEngineConfig().apply {
            mAppId = appId; mContext = context.applicationContext; mEventHandler = events
        })
        try {
            System.loadLibrary("nosmai")
            shareHandle = EglBaseProvider.instance().rootEglBase.eglBaseContext.nativeEglContext
            NosmaiSDK.setAgoraShareContext(shareHandle)
        } catch (_: Throwable) { shareHandle = 0 }
        engine?.enableVideo()
        engine?.setLocalVideoMirrorMode(Constants.VIDEO_MIRROR_MODE_DISABLED)
        engine?.setVideoEncoderConfiguration(VideoEncoderConfiguration(
            VideoEncoderConfiguration.VideoDimensions(720, 1280),
            VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30, 2500,
            VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
        ).apply {
            minBitrate = 1200
            mirrorMode = VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
        })
    }

    fun nativeHandle(): Long = engine?.nativeHandle ?: 0L

    fun startStreaming(token: String?, channelName: String, uid: Int): Boolean {
        val rtc = engine ?: return false
        if (channelName.isBlank() || leaving || retiring != null || drain != null) return false
        current?.let { return it.channel == channelName && it.uid == uid && it.active.get() }
        var bridge: AgoraTextureBridge? = null
        try {
            // A nonzero handle alone does NOT prove sharing: SDK initialization
            // may have happened first, or EGL may have fallen back to unshared.
            if (shareHandle != 0L && SdkTextureFrames.shares(shareHandle)) {
                val context = EglBaseProvider.instance().rootEglBase.eglBaseContext
                TextureBufferHelper.create("nosmai-agora-copy", context)?.let {
                    bridge = AgoraTextureBridge(it, rtc)
                }
            }
        } catch (_: LinkageError) {
            // Older SDKs do not expose the synchronized texture protocol.
        } catch (error: Exception) {
            Log.w("NosmaiAgoraBridge", "Using I420 fallback", error)
        }
        val session = Session(channelName, uid, bridge)
        current = session
        try {
            clearCallbacks()
            NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.DUAL_OUTPUT)
            rtc.setExternalVideoSource(true, bridge != null, Constants.ExternalVideoSourceType.VIDEO_FRAME)
            rtc.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            if (bridge != null) {
                val output = bridge!!
                NosmaiSDK.setTextureFrameCallback { tex, w, h, ts, _ ->
                    if (current !== session || !session.active.get() || !session.joined.get()) {
                        NosmaiSDK.releaseStreamSlot(tex)
                    } else output.pushCopy(tex, w, h, ts) { NosmaiSDK.releaseStreamSlot(tex) }
                }
            } else {
                NosmaiSDK.setFrameCallback { frame ->
                    if (frame != null && frame.format == 1 && current === session &&
                        session.active.get() && session.joined.get()) {
                        pushPixels(session, rtc, frame.pixelBuffer, frame.width, frame.height, frame.timestampNs)
                    }
                }
            }
            val options = ChannelMediaOptions().apply {
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                publishCameraTrack = false
                publishCustomVideoTrack = true
                publishCustomAudioTrack = true
                autoSubscribeVideo = true; autoSubscribeAudio = true
            }
            if (rtc.joinChannel(token?.takeIf { it.isNotBlank() }, channelName, uid, options) != 0) {
                retire(session)
                finishDrain()
                rtc.setExternalVideoSource(false, bridge != null, Constants.ExternalVideoSourceType.VIDEO_FRAME)
                retiring = null
                return false
            }
            return true
        } catch (error: Throwable) {
            if (retiring !== session) retire(session)
            // Keep a failed drain available for a later stop/dispose retry.
            try {
                finishDrain()
                rtc.setExternalVideoSource(false, bridge != null, Constants.ExternalVideoSourceType.VIDEO_FRAME)
                retiring = null
            } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun retire(session: Session) {
        retiring = session
        session.active.set(false)
        if (current === session) current = null
        clearCallbacks()
        NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.PREVIEW_ONLY)
        val textureDrain = session.bridge?.release() ?: CompletableFuture.completedFuture(null)
        val pixelDrain = CompletableFuture<Void>()
        pixels.execute { pixelDrain.complete(null) }
        drain = CompletableFuture.allOf(textureDrain, pixelDrain)
    }

    private fun finishDrain() {
        // This runs on the plugin command worker, never on UI, RTC, or GL threads.
        // Preserve an incomplete drain on timeout so a later start cannot overlap it.
        drain?.get(5, TimeUnit.SECONDS)
        drain = null
    }

    fun stopStreaming(): Boolean {
        val session = current ?: retiring
        if (session != null && retiring !== session) retire(session)
        finishDrain()
        val rtc = engine ?: return true
        rtc.setExternalVideoSource(false, session?.bridge != null, Constants.ExternalVideoSourceType.VIDEO_FRAME)
        if (session == null) return true
        leaving = true
        val result = rtc.leaveChannel()
        if (result != 0) leaving = false else retiring = null
        return result == 0
    }

    fun dispose() {
        stopStreaming()
        pixels.shutdown()
        engine = null
        RtcEngine.destroy()
    }

    private fun clearCallbacks() {
        NosmaiSDK.setTextureFrameCallback(null)
        NosmaiSDK.setFrameCallback(null)
    }

    private fun pushPixels(s: Session, rtc: RtcEngine, data: ByteArray?, w: Int, h: Int, ts: Long) {
        if (data == null || w <= 0 || h <= 0 || w > 4096 || h > 4096 ||
            !s.pixelPending.compareAndSet(false, true)) return
        var storage: Pixels? = null
        try {
            val needed = w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2)
            if (data.size < needed) { s.pixelPending.set(false); return }
            storage = s.freePixels.poll()
            if (storage == null) {
                if (s.pixelCount.get() >= 3) { s.pixelPending.set(false); return }
                storage = Pixels(w, h)
                s.pixelCount.incrementAndGet()
            } else if (storage.width != w || storage.height != h) storage = Pixels(w, h)
            val owned = storage
            // SDK callback byte arrays are pooled. Copy before returning from the
            // callback; a queued worker must never read a later frame's reused array.
            owned.y.clear(); owned.u.clear(); owned.v.clear()
            val ySize = w * h; val uvSize = owned.uvWidth * owned.uvHeight
            owned.y.put(data, 0, ySize).rewind()
            owned.u.put(data, ySize, uvSize).rewind()
            owned.v.put(data, ySize + uvSize, uvSize).rewind()
            pixels.execute {
                var frame: VideoFrame? = null
                try {
                    if (current !== s || !s.active.get() || !s.joined.get()) return@execute
                    val buffer = JavaI420Buffer.wrap(w, h, owned.y, w, owned.u, owned.uvWidth,
                        owned.v, owned.uvWidth) { s.freePixels.offer(owned) }
                    frame = VideoFrame(buffer, 0, ts)
                    if (current === s && s.active.get()) rtc.pushExternalVideoFrame(frame)
                } catch (error: Throwable) {
                    Log.e("NosmaiAgoraBridge", "I420 delivery failed", error)
                } finally {
                    if (frame != null) frame.release() else s.freePixels.offer(owned)
                    s.pixelPending.set(false)
                }
            }
        } catch (error: Throwable) {
            storage?.let { s.freePixels.offer(it) }
            s.pixelPending.set(false)
            Log.e("NosmaiAgoraBridge", "I420 copy failed", error)
        }
    }
}
