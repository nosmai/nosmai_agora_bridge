import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nosmai_agora_bridge/nosmai_agora_bridge_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final platform = MethodChannelNosmaiAgoraBridge();
  const channel = MethodChannel('nosmai_agora_bridge');
  final calls = <MethodCall>[];
  Object? response;
  setUp(() {
    calls.clear();
    response = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return response;
    });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  test('platform version keeps its method contract', () async {
    response = '42';
    expect(await platform.getPlatformVersion(), '42');
    expect(calls.single.method, 'getPlatformVersion');
  });
  test('initialization forwards app ID and native handle', () async {
    response = 123;
    expect(await platform.initializeNative('app'), 123);
    expect(calls.single.method, 'native_init');
    expect(calls.single.arguments, {'appId': 'app'});
  });
  test('start keeps channel, token and UID unchanged', () async {
    response = true;
    expect(
        await platform.startStreaming(
            channelName: 'room', token: 'test-token', uid: 7),
        isTrue);
    expect(calls.single.method, 'native_start_streaming');
    expect(calls.single.arguments,
        {'channelName': 'room', 'token': 'test-token', 'uid': 7});
  });
  test('start defaults and native rejection are preserved', () async {
    response = false;
    expect(await platform.startStreaming(channelName: 'room'), isFalse);
    expect(calls.single.arguments,
        {'channelName': 'room', 'token': null, 'uid': 0});
  });
  test('stop forwards success and defaults missing reply to false', () async {
    response = true;
    expect(await platform.stopStreaming(), isTrue);
    expect(calls.last.method, 'native_stop_streaming');
    response = null;
    expect(await platform.stopStreaming(), isFalse);
  });
  test('dispose keeps its native method contract', () async {
    await platform.dispose();
    expect(calls.single.method, 'native_dispose');
  });
}
