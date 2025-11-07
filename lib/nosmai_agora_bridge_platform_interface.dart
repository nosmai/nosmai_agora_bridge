import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'nosmai_agora_bridge_method_channel.dart';

abstract class NosmaiAgoraBridgePlatform extends PlatformInterface {
  /// Constructs a NosmaiAgoraBridgePlatform.
  NosmaiAgoraBridgePlatform() : super(token: _token);

  static final Object _token = Object();

  static NosmaiAgoraBridgePlatform _instance = MethodChannelNosmaiAgoraBridge();

  /// The default instance of [NosmaiAgoraBridgePlatform] to use.
  ///
  /// Defaults to [MethodChannelNosmaiAgoraBridge].
  static NosmaiAgoraBridgePlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [NosmaiAgoraBridgePlatform] when
  /// they register themselves.
  static set instance(NosmaiAgoraBridgePlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  /// Initialize native VideoRawDataController and return Agora native handle
  Future<int> initializeNative(String appId) {
    throw UnimplementedError('initializeNative() has not been implemented.');
  }

  /// Dispose native resources
  Future<void> dispose() {
    throw UnimplementedError('dispose() has not been implemented.');
  }
}
