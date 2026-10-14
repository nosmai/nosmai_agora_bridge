# Nosmai Agora Bridge for Flutter

Connect the Nosmai Effects camera pipeline to Agora RTC in Flutter applications.
The bridge keeps video-frame transfer native, so processed frames do not travel
through Dart before Agora publishes them.

> `0.1.0-beta.1` is a device-testing prerelease. It uses
> `nosmai_effects_sdk 2.0.2-beta.2` and Android Effects SDK `3.0.7-beta.2`.

## Features

- Nosmai beauty, makeup, reshape, AR, LUT, and background effects in Agora streams
- Android shared-EGL GPU texture delivery with a bounded I420 fallback
- Native frame transfer without per-frame Dart callbacks
- Serialized start, stop, reconnect, and cleanup operations
- Existing iOS Agora video-observer integration

## Beta installation

Add the beta bridge to your app:

```yaml
dependencies:
  nosmai_agora_bridge:
    git:
      url: https://github.com/nosmai/nosmai_agora_bridge.git
      ref: v0.1.0-beta.1

  agora_rtc_engine: ^6.5.3
```

The bridge depends on the matching Nosmai Effects Flutter beta. Android resolves
the versioned Nosmai AAR automatically from the official GitHub release; do not
copy an AAR into `android/app/libs`.

Then run:

```sh
flutter pub get
```

## Requirements

- Flutter 3.29 or later
- Android API 24 or later on `arm64-v8a` or `armeabi-v7a`
- iOS 15 or later on a physical arm64 device
- Camera and microphone permissions
- Valid Nosmai and Agora credentials

## Android flow

On Android, the bridge must create the shared Agora EGL context before Nosmai is
initialized. Show `NosmaiCameraPreview`, then start streaming from its
`onInitialized` callback.

```dart
final nativeHandle = await NosmaiAgoraBridge.getNativeHandle(
  agoraAppId: agoraAppId,
);

final engine = createAgoraRtcEngine(sharedNativeHandle: nativeHandle);
await engine.initialize(RtcEngineContext(
  appId: agoraAppId,
  channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
));

final initialized = await NosmaiFlutter.initialize(nosmaiLicenseKey);
if (!initialized) {
  throw StateError('Nosmai initialization failed');
}

NosmaiCameraPreview(
  onInitialized: () async {
    final accepted = await NosmaiAgoraBridge.startStreaming(
      channelName: channelName,
      token: agoraToken,
      uid: 0,
    );
    if (!accepted) {
      throw StateError('Agora rejected the stream start request');
    }
  },
  onError: (message) => debugPrint(message),
)
```

`startStreaming` owns the Android Agora video publication flow. Do not also call
`startPreview` or `joinChannel` from Dart on Android. A `true` result means Agora
accepted the request; use Agora event callbacks to confirm the channel join.

Apply effects through the normal Nosmai API:

```dart
await NosmaiFlutter.instance.applySkinSmoothing(0.6);

final filters = await NosmaiFlutter.instance.getLocalFilters();
if (filters.isNotEmpty) {
  await NosmaiFlutter.instance.applyFilter(filters.first.path);
}
```

Switch the Android camera through Nosmai:

```dart
await NosmaiFlutter.instance.switchCamera();
```

Stop in this order:

```dart
await NosmaiAgoraBridge.stopStreaming();
await engine.release();
await NosmaiAgoraBridge.disposeNative();
```

## iOS flow

The current iOS bridge uses Agora's native video observer. Initialize Nosmai,
create the shared Agora engine, and use the normal Agora preview/join flow:

```dart
final initialized = await NosmaiFlutter.initialize(nosmaiLicenseKey);
if (!initialized) throw StateError('Nosmai initialization failed');

final nativeHandle = await NosmaiAgoraBridge.getNativeHandle(
  agoraAppId: agoraAppId,
);
final engine = createAgoraRtcEngine(sharedNativeHandle: nativeHandle);

await engine.initialize(RtcEngineContext(
  appId: agoraAppId,
  channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
));
await engine.enableVideo();
await engine.startPreview();
await engine.joinChannel(
  token: agoraToken,
  channelId: channelName,
  uid: 0,
  options: const ChannelMediaOptions(
    clientRoleType: ClientRoleType.clientRoleBroadcaster,
    channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
  ),
);
```

After switching the iOS Agora camera, notify the bridge:

```dart
await engine.switchCamera();
await NosmaiAgoraBridge.notifyCameraSwitch();
```

## API

| Method | Purpose |
| --- | --- |
| `getNativeHandle(agoraAppId:)` | Create the native bridge and return the shared Agora handle |
| `startStreaming(...)` | Start Android publication from the Nosmai preview |
| `stopStreaming()` | Stop Android publication and restore preview-only rendering |
| `notifyCameraSwitch()` | Notify the iOS observer after an Agora camera switch |
| `disposeNative()` | Release native bridge resources |
| `isInitialized` | Report whether the native bridge handle exists |

## Example and validation

The [`example`](example/) app contains the complete platform-aware integration.
Replace its placeholder credentials before running it on physical devices.

```sh
flutter test
python3 tools/run_texture_bridge_tests.py
python3 tools/run_controller_tests.py
cd example && flutter build apk --debug
```

The included host-side tests cover lifecycle and frame ownership. A production
release should also be tested between two physical devices for local/remote
orientation, audio, filters, reconnects, camera switching, and thermal behavior.

## Support

- [Nosmai Effects documentation](https://nosmai.com/docs/effects/introduction/)
- [Flutter Agora integration guide](https://nosmai.com/docs/effects/integrations/agora/flutter/)
- [GitHub issues](https://github.com/nosmai/nosmai_agora_bridge/issues)
- Email: admin@nosmai.com
