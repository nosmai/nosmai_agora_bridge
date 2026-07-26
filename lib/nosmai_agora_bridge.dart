import 'dart:io' show Platform;
import 'nosmai_agora_bridge_platform_interface.dart';

/// NosmaiAgoraBridge - Seamless integration of Nosmai filters with Agora RTC
///
/// This package handles native video frame processing for both Android and iOS,
/// allowing you to apply Nosmai filters to Agora video streams without writing
/// any platform-specific code.
///
/// Example:
/// ```dart
/// // Get native handle for Nosmai integration
/// final nativeHandle = await NosmaiAgoraBridge.getNativeHandle(
///   agoraAppId: 'YOUR_AGORA_APP_ID',
/// );
///
/// // Create Agora engine with shared native handle
/// _engine = createAgoraRtcEngine(sharedNativeHandle: nativeHandle);
///
/// // Initialize engine with your configuration
/// await _engine.initialize(RtcEngineContext(
///   appId: 'YOUR_AGORA_APP_ID',
///   channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
/// ));
///
/// // Use engine normally
/// await _engine.enableVideo();
/// await _engine.joinChannel(...);
///
/// // Cleanup when done
/// await _engine.leaveChannel();
/// await _engine.release();
/// await NosmaiAgoraBridge.disposeNative();
/// ```
class NosmaiAgoraBridge {
  static int? _nativeHandle;

  /// Get native handle for Nosmai-Agora integration
  ///
  /// This method initializes the native VideoRawDataController and returns
  /// a handle that can be used with createAgoraRtcEngine().
  ///
  /// Parameters:
  /// - [agoraAppId]: Your Agora App ID from console.agora.io
  ///
  /// Returns: Native handle to use with createAgoraRtcEngine(sharedNativeHandle:)
  ///
  /// Example:
  /// ```dart
  /// final nativeHandle = await NosmaiAgoraBridge.getNativeHandle(
  ///   agoraAppId: 'YOUR_APP_ID',
  /// );
  ///
  /// _engine = createAgoraRtcEngine(sharedNativeHandle: nativeHandle);
  /// ```
  static Future<int> getNativeHandle({
    required String agoraAppId,
  }) async {
    _nativeHandle =
        await NosmaiAgoraBridgePlatform.instance.initializeNative(agoraAppId);
    return _nativeHandle!;
  }

  /// Dispose native Nosmai resources
  ///
  /// Call this after releasing your RtcEngine instance to clean up
  /// the native VideoRawDataController.
  ///
  /// Example:
  /// ```dart
  /// await _engine.leaveChannel();
  /// await _engine.release();
  /// await NosmaiAgoraBridge.disposeNative();
  /// ```
  static Future<void> disposeNative() async {
    if (_nativeHandle != null) {
      await NosmaiAgoraBridgePlatform.instance.dispose();
      _nativeHandle = null;
    }
  }

  /// Start publishing the Nosmai processed camera output to Agora.
  ///
  /// Android uses the same texture-first path as the native Nosmai demo when
  /// available. Call [getNativeHandle] before Nosmai SDK initialization, create
  /// the shared Agora engine from that handle, then call this after the Nosmai
  /// preview is visible.
  static Future<bool> startStreaming({
    required String channelName,
    String? token,
    int uid = 0,
  }) {
    return NosmaiAgoraBridgePlatform.instance.startStreaming(
      channelName: channelName,
      token: token,
      uid: uid,
    );
  }

  /// Stop publishing and return Nosmai to preview-only rendering.
  static Future<bool> stopStreaming() {
    return NosmaiAgoraBridgePlatform.instance.stopStreaming();
  }

  /// Check if native bridge is initialized
  static bool get isInitialized => _nativeHandle != null;

  /// Notify native side that camera was switched
  ///
  /// Call this AFTER calling rtcEngine.switchCamera() to ensure
  /// the native video processor knows which camera is active.
  ///
  /// **Note**: This is only required for iOS. Android automatically detects
  /// camera switches via VideoFrame.sourceType property.
  ///
  /// Example:
  /// ```dart
  /// await _engine.switchCamera();
  /// await NosmaiAgoraBridge.notifyCameraSwitch();  // Auto-handled for platform
  /// ```
  static Future<void> notifyCameraSwitch() async {
    // Only notify on iOS - Android auto-detects via VideoFrame.sourceType
    if (Platform.isIOS) {
      await NosmaiAgoraBridgePlatform.instance.notifyCameraSwitch();
    }
    // Android: No-op, camera detected automatically
  }

  /// Get platform version for debugging
  static Future<String?> getPlatformVersion() {
    return NosmaiAgoraBridgePlatform.instance.getPlatformVersion();
  }
}
