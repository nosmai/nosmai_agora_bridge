package com.example.nosmai_agora_bridge

import android.content.Context
import android.util.Log
import io.agora.base.VideoFrame
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.IVideoFrameObserver
import io.agora.rtc2.video.IVideoFrameObserver.POSITION_POST_CAPTURER
import io.agora.rtc2.video.IVideoFrameObserver.PROCESS_MODE_READ_WRITE
import io.agora.rtc2.video.IVideoFrameObserver.VIDEO_PIXEL_I420
import com.nosmai.effect.api.NosmaiSDK


class VideoRawDataController(context: Context, myAppId: String ) {

    private var rtcEngine: RtcEngine? = null
    private var isPipelineReady = false
    private var isFrontCamera = true

    init {
        rtcEngine = RtcEngine.create(RtcEngineConfig().apply {
            mAppId = myAppId
            mContext = context.applicationContext
            mEventHandler = object : IRtcEngineEventHandler() { }
        })

        // Disable Agora's local mirror - we want local preview to stay as-is
        rtcEngine!!.setLocalVideoMirrorMode(io.agora.rtc2.Constants.VIDEO_MIRROR_MODE_DISABLED)

        // Configure video encoder - frames are already UN-mirrored by Nosmai, send as-is
        val encoderConfig = io.agora.rtc2.video.VideoEncoderConfiguration().apply {
            mirrorMode = io.agora.rtc2.video.VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
        }
        rtcEngine!!.setVideoEncoderConfiguration(encoderConfig)
        Log.i("VideoRawDataController", "Video encoder mirror DISABLED - Nosmai handles un-mirroring")

        // Set initial camera facing and mirror state for Nosmai
        NosmaiSDK.setCameraFacing(isFrontCamera)
        // setMirrorX is for INTERNAL pipeline (PlatformView - local preview)
        // Frames are already mirrored, so DON'T flip for local preview
        NosmaiSDK.setMirrorX(false)

        rtcEngine!!.registerVideoFrameObserver(object : IVideoFrameObserver {
            override fun onCaptureVideoFrame(sourceType: Int, videoFrame: VideoFrame?): Boolean {
                videoFrame?.apply {
                    val i420Buffer = buffer.toI420()

                    if (!isPipelineReady) {
                        try {
                            val pipelineInitialized = NosmaiSDK.initializeExternalFramePipeline(
                                i420Buffer.width,
                                i420Buffer.height
                            )

                            if (pipelineInitialized) {
                                isPipelineReady = true
                                NosmaiSDK.setExternalFrameMode(true)
                                Log.i("VideoRawDataController", "External pipeline initialized")
                            } else {
                                Log.e("VideoRawDataController", "Failed to initialize pipeline")
                                return@apply
                            }
                        } catch (e: Exception) {
                            Log.e("VideoRawDataController", "Pipeline init failed: ${e.message}")
                            return@apply
                        }
                    }

                    if (isPipelineReady) {
                        try {
                            val success = NosmaiSDK.processExternalI420InPlace(
                                i420Buffer.dataY,
                                i420Buffer.dataV,
                                i420Buffer.dataU,
                                i420Buffer.width,
                                i420Buffer.height,
                                i420Buffer.strideY,
                                i420Buffer.strideV,
                                i420Buffer.strideU,
                                rotation,
                                isFrontCamera  // Front camera: FLIP to un-mirror for remote users
                            )

                            if (!success) {
                                Log.w("VideoRawDataController", "⚠️ Frame processing failed")
                            }
                        } catch (e: Exception) {
                            Log.e("VideoRawDataController", "Frame processing error: ${e.message}")
                        }
                    }

                    videoFrame.replaceBuffer(i420Buffer, videoFrame.rotation, videoFrame.timestampNs)
                }

                return true
            }

            override fun onPreEncodeVideoFrame(sourceType: Int, videoFrame: VideoFrame?): Boolean {
                return false;
            }

            override fun onMediaPlayerVideoFrame(
                videoFrame: VideoFrame?,
                mediaPlayerId: Int
            ): Boolean {
                return false;
            }

            override fun onRenderVideoFrame(
                channelId: String?,
                uid: Int,
                videoFrame: VideoFrame?
            ): Boolean {
                return false;
            }

            override fun getVideoFrameProcessMode(): Int {
                return PROCESS_MODE_READ_WRITE
            }

            override fun getVideoFormatPreference(): Int {
                return VIDEO_PIXEL_I420
            }

            override fun getRotationApplied(): Boolean {
                return true  // We handle rotation in Nosmai processing
            }

            override fun getMirrorApplied(): Boolean {
                // Frames are UN-mirrored by Nosmai (setMirrorX flips them)
                // So tell Agora: NO mirror applied, send as-is
                return false
            }

            override fun getObservedFramePosition(): Int {
                return POSITION_POST_CAPTURER
            }

        })
    }

    fun nativeHandle() = rtcEngine!!.nativeHandle

    fun switchCamera() {
        isFrontCamera = !isFrontCamera
        rtcEngine?.switchCamera()

        // Update Nosmai mirror state for new camera
        NosmaiSDK.setCameraFacing(isFrontCamera)
        // setMirrorX for INTERNAL pipeline (local preview)
        // Frames already mirrored from camera, don't flip
        NosmaiSDK.setMirrorX(false)

        Log.i("VideoRawDataController", "Camera switched → isFrontCamera=$isFrontCamera (external pipeline flips for un-mirror)")
    }

    fun dispose() {
        rtcEngine!!.registerVideoFrameObserver(null)
        RtcEngine.destroy()
        rtcEngine = null
    }
}