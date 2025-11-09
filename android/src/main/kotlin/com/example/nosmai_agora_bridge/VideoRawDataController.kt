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

    init {
        rtcEngine = RtcEngine.create(RtcEngineConfig().apply {
            mAppId = myAppId
            mContext = context.applicationContext
            mEventHandler = object : IRtcEngineEventHandler() { }
        })

        rtcEngine!!.setLocalVideoMirrorMode(io.agora.rtc2.Constants.VIDEO_MIRROR_MODE_DISABLED)

        val encoderConfig = io.agora.rtc2.video.VideoEncoderConfiguration().apply {
            dimensions = io.agora.rtc2.video.VideoEncoderConfiguration.VD_640x360.apply {
                width = 720
                height = 1280
            }
            mirrorMode = io.agora.rtc2.video.VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
        }
        rtcEngine!!.setVideoEncoderConfiguration(encoderConfig)

        NosmaiSDK.setCameraFacing(true)  // Default front camera
        NosmaiSDK.setMirrorX(false)

        rtcEngine!!.registerVideoFrameObserver(object : IVideoFrameObserver {
            override fun onCaptureVideoFrame(sourceType: Int, videoFrame: VideoFrame?): Boolean {
                videoFrame?.apply {
                    val i420Buffer = buffer.toI420()

                    // ✅ Detect camera from VideoFrame's sourceType property
                    val frameSourceType = videoFrame.sourceType
                    val isFrontCamera = (frameSourceType == VideoFrame.SourceType.kFrontCamera)

                    if (!isPipelineReady) {
                        try {
                            val pipelineInitialized = NosmaiSDK.initializeExternalFramePipeline(
                                i420Buffer.width,
                                i420Buffer.height
                            )

                            if (pipelineInitialized) {
                                isPipelineReady = true
                                NosmaiSDK.setExternalFrameMode(true)
                            } else {
                                return@apply
                            }
                        } catch (e: Exception) {
                            return@apply
                        }
                    }

                    if (isPipelineReady) {
                        try {
                            NosmaiSDK.setCameraFacing(isFrontCamera)

                            NosmaiSDK.processExternalI420InPlace(
                                i420Buffer.dataY,
                                i420Buffer.dataU,
                                i420Buffer.dataV,
                                i420Buffer.width,
                                i420Buffer.height,
                                i420Buffer.strideY,
                                i420Buffer.strideU,
                                i420Buffer.strideV,
                                rotation,
                                isFrontCamera
                            )
                        } catch (e: Exception) {
                            // Silently ignore errors
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
                return true
            }

            override fun getMirrorApplied(): Boolean {
                return false
            }

            override fun getObservedFramePosition(): Int {
                return POSITION_POST_CAPTURER
            }

        })
    }

    fun nativeHandle() = rtcEngine!!.nativeHandle

    fun switchCamera() {
        rtcEngine?.switchCamera()
    }

    fun dispose() {
        rtcEngine!!.registerVideoFrameObserver(null)
        RtcEngine.destroy()
        rtcEngine = null
    }
}