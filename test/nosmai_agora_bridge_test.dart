import 'package:flutter_test/flutter_test.dart';
import 'package:nosmai_agora_bridge/nosmai_agora_bridge.dart';
import 'package:nosmai_agora_bridge/nosmai_agora_bridge_platform_interface.dart';
import 'package:nosmai_agora_bridge/nosmai_agora_bridge_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockNosmaiAgoraBridgePlatform extends NosmaiAgoraBridgePlatform
    with MockPlatformInterfaceMixin {
  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final NosmaiAgoraBridgePlatform initialPlatform =
      NosmaiAgoraBridgePlatform.instance;

  test('$MethodChannelNosmaiAgoraBridge is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelNosmaiAgoraBridge>());
  });

  tearDown(() => NosmaiAgoraBridgePlatform.instance = initialPlatform);

  test('getPlatformVersion', () async {
    MockNosmaiAgoraBridgePlatform fakePlatform =
        MockNosmaiAgoraBridgePlatform();
    NosmaiAgoraBridgePlatform.instance = fakePlatform;

    expect(await NosmaiAgoraBridge.getPlatformVersion(), '42');
  });
}
