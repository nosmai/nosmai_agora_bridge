package com.example.nosmai_agora_bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture

/** Serial lifecycle commands off the platform/RTC/GL threads. */
class NosmaiAgoraBridgePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var detached = CompletableFuture.completedFuture<Void>(null)
    private var commands = Executors.newSingleThreadExecutor { Thread(it, "nosmai-agora-control") }
    private var controller: VideoRawDataController? = null
    private var initializedAppId: String? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        if (commands.isShutdown) {
            val previous = detached
            commands = Executors.newSingleThreadExecutor { Thread(it, "nosmai-agora-control") }
            // A reattached engine must not race the old engine's native destroy.
            commands.execute { previous.join() }
        }
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "nosmai_agora_bridge")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "getPlatformVersion") {
            result.success("Android ${android.os.Build.VERSION.RELEASE}")
            return
        }
        if (call.method !in setOf("native_init", "native_dispose", "native_start_streaming", "native_stop_streaming")) {
            result.notImplemented(); return
        }
        commands.execute {
            try {
                val value: Any? = when (call.method) {
                    "native_init" -> {
                        val appId = call.argument<String>("appId")
                        require(!appId.isNullOrBlank()) { "App ID is required" }
                        check(controller == null || initializedAppId == appId) { "Dispose the previous engine before changing App ID" }
                        if (controller == null) {
                            controller = VideoRawDataController(context, appId)
                            initializedAppId = appId
                        }
                        controller!!.nativeHandle()
                    }
                    "native_start_streaming" -> {
                        val current = controller ?: throw IllegalStateException("Call getNativeHandle before startStreaming")
                        val name = call.argument<String>("channelName")
                        require(!name.isNullOrBlank()) { "channelName is required" }
                        current.startStreaming(call.argument<String>("token"), name,
                            call.argument<Number>("uid")?.toInt() ?: 0)
                    }
                    "native_stop_streaming" -> controller?.stopStreaming() ?: true
                    else -> {
                        controller?.dispose()
                        controller = null; initializedAppId = null
                        true
                    }
                }
                main.post { result.success(value) }
            } catch (error: Throwable) {
                val code = when {
                    error is IllegalArgumentException -> "INVALID_ARGS"
                    call.method == "native_init" -> "INIT_ERROR"
                    call.method == "native_dispose" -> "DISPOSE_ERROR"
                    call.method == "native_stop_streaming" -> "STOP_STREAM_ERROR"
                    controller == null -> "NOT_INITIALIZED"
                    else -> "START_STREAM_ERROR"
                }
                main.post { result.error(code, error.message, null) }
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        val completion = CompletableFuture<Void>()
        detached = completion
        commands.execute {
            try { controller?.dispose() }
            catch (error: Throwable) { android.util.Log.e("NosmaiAgoraBridge", "Detach cleanup failed", error) }
            finally { controller = null; initializedAppId = null; completion.complete(null) }
        }
        commands.shutdown()
    }
}
