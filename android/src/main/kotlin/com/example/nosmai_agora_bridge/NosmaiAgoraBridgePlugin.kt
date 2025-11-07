package com.example.nosmai_agora_bridge

import android.content.Context
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** NosmaiAgoraBridgePlugin */
class NosmaiAgoraBridgePlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var videoRawDataController: VideoRawDataController? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "nosmai_agora_bridge")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "native_init" -> {
                val appId = call.argument<String>("appId")
                if (appId != null) {
                    try {
                        videoRawDataController = VideoRawDataController(context, appId)
                        val nativeHandle = videoRawDataController?.nativeHandle() ?: 0L
                        result.success(nativeHandle)
                    } catch (e: Exception) {
                        result.error("INIT_ERROR", "Failed to initialize: ${e.message}", null)
                    }
                } else {
                    result.error("INVALID_ARGS", "App ID is required", null)
                }
            }
            "native_dispose" -> {
                try {
                    videoRawDataController?.dispose()
                    videoRawDataController = null
                    result.success(true)
                } catch (e: Exception) {
                    result.error("DISPOSE_ERROR", "Failed to dispose: ${e.message}", null)
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        videoRawDataController?.dispose()
        videoRawDataController = null
    }
}
