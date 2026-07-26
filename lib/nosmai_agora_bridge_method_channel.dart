import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'nosmai_agora_bridge_platform_interface.dart';

/// An implementation of [NosmaiAgoraBridgePlatform] that uses method channels.
class MethodChannelNosmaiAgoraBridge extends NosmaiAgoraBridgePlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('nosmai_agora_bridge');

  @override
  Future<String?> getPlatformVersion() async {
    final version =
        await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }

  @override
  Future<int> initializeNative(String appId) async {
    final nativeHandle = await methodChannel.invokeMethod<int>('native_init', {
      'appId': appId,
    });
    return nativeHandle ?? 0;
  }

  @override
  Future<void> dispose() async {
    await methodChannel.invokeMethod('native_dispose');
  }

  @override
  Future<bool> startStreaming({
    required String channelName,
    String? token,
    int uid = 0,
  }) async {
    final result =
        await methodChannel.invokeMethod<bool>('native_start_streaming', {
      'channelName': channelName,
      'token': token,
      'uid': uid,
    });
    return result ?? false;
  }

  @override
  Future<bool> stopStreaming() async {
    final result =
        await methodChannel.invokeMethod<bool>('native_stop_streaming');
    return result ?? false;
  }

  @override
  Future<void> notifyCameraSwitch() async {
    await methodChannel.invokeMethod('notify_camera_switch');
  }
}
