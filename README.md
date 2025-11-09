# nosmai_agora_bridge

Seamless integration of **Nosmai filters** with **Agora RTC Engine** for Flutter. Apply real-time video filters to your Agora streams **without writing any native code**.

## Features

- **Zero Native Code** - All platform-specific code is handled internally
- **Cross-Platform** - Works on Android and iOS
- **Minimal Integration** - Add Nosmai filters to existing Agora apps with just 2 lines
- **Full Control** - Manage your RtcEngine configuration as usual
- **High Performance** - Optimized video frame processing pipeline

## Problem Solved

Integrating Nosmai filters with Agora typically requires:
- Writing native code in Kotlin/Java (Android)
- Writing native code in Objective-C/Swift (iOS)
- Understanding video frame processing pipelines
- Managing native handles and memory

**This package eliminates all of that.**

## Installation

Add to your `pubspec.yaml`:

```yaml
dependencies:
  nosmai_agora_bridge:
    git:
      url: https://github.com/nosmai/nosmai_agora_bridge.git

  agora_rtc_engine: ^x.x.x
  nosmai_camera_sdk: ^x.x.x
```

Run:
```bash
flutter pub get
```

## Usage

### 1. Initialize Nosmai SDK

```dart
import 'package:nosmai_camera_sdk/nosmai_camera_sdk.dart';

await NosmaiFlutter.initialize('YOUR_NOSMAI_KEY');
```

### 2. Get Native Handle

```dart
import 'package:nosmai_agora_bridge/nosmai_agora_bridge.dart';

final nativeHandle = await NosmaiAgoraBridge.getNativeHandle(
  agoraAppId: 'YOUR_AGORA_APP_ID',
);
```

### 3. Create Agora Engine

```dart
_engine = createAgoraRtcEngine(sharedNativeHandle: nativeHandle);
```

### 4. Use Agora Normally

```dart
await _engine.initialize(RtcEngineContext(
  appId: 'YOUR_AGORA_APP_ID',
  channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
));

await _engine.enableVideo();
await _engine.startPreview();

await _engine.joinChannel(
  token: token,
  channelId: channelId,
  uid: 0,
  options: const ChannelMediaOptions(
    clientRoleType: ClientRoleType.clientRoleBroadcaster,
  ),
);
```

### 5. Apply Filters

```dart
// Get available filters
final filters = await NosmaiFlutter.instance.getLocalFilters();

// Apply a filter (appears in live stream)
await NosmaiFlutter.instance.applyFilter(filters[0].path);

// Remove filters
await NosmaiFlutter.instance.removeAllFilters();
```

### 6. Switch Camera

```dart
// Use Agora's standard method
await _engine.switchCamera();
```

### 7. Cleanup

```dart
await _engine.leaveChannel();
await _engine.release();
await NosmaiAgoraBridge.disposeNative();
```

## Complete Example

```dart
import 'package:flutter/material.dart';
import 'package:agora_rtc_engine/agora_rtc_engine.dart';
import 'package:nosmai_agora_bridge/nosmai_agora_bridge.dart';
import 'package:nosmai_camera_sdk/nosmai_camera_sdk.dart';
import 'package:permission_handler/permission_handler.dart';

class LiveStreamScreen extends StatefulWidget {
  @override
  State<LiveStreamScreen> createState() => _LiveStreamScreenState();
}

class _LiveStreamScreenState extends State<LiveStreamScreen> {
  RtcEngine? _engine;
  bool _isJoined = false;
  Set<int> _remoteUids = {};

  @override
  void initState() {
    super.initState();
    _initAgora();
  }

  Future<void> _initAgora() async {
    await [Permission.camera, Permission.microphone].request();

    // Initialize Nosmai
    await NosmaiFlutter.initialize('YOUR_NOSMAI_KEY');

    // Get native handle
    final nativeHandle = await NosmaiAgoraBridge.getNativeHandle(
      agoraAppId: 'YOUR_AGORA_APP_ID',
    );

    // Create engine with shared handle
    _engine = createAgoraRtcEngine(sharedNativeHandle: nativeHandle);

    // Initialize engine
    await _engine!.initialize(RtcEngineContext(
      appId: 'YOUR_AGORA_APP_ID',
      channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
    ));

    // Register event handlers
    _engine!.registerEventHandler(
      RtcEngineEventHandler(
        onJoinChannelSuccess: (connection, elapsed) {
          setState(() => _isJoined = true);
        },
        onUserJoined: (connection, remoteUid, elapsed) {
          setState(() => _remoteUids.add(remoteUid));
        },
        onUserOffline: (connection, remoteUid, reason) {
          setState(() => _remoteUids.remove(remoteUid));
        },
      ),
    );

    // Start video
    await _engine!.enableVideo();
    await _engine!.startPreview();

    // Join channel
    await _engine!.joinChannel(
      token: 'YOUR_TOKEN',
      channelId: 'test_channel',
      uid: 0,
      options: const ChannelMediaOptions(
        clientRoleType: ClientRoleType.clientRoleBroadcaster,
      ),
    );
  }

  @override
  void dispose() {
    _engine?.leaveChannel();
    _engine?.release();
    NosmaiAgoraBridge.disposeNative();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          if (_isJoined)
            AgoraVideoView(
              controller: VideoViewController(
                rtcEngine: _engine!,
                canvas: const VideoCanvas(uid: 0),
              ),
            ),
        ],
      ),
    );
  }
}
```

## Applying Filters

### Local Filters

```dart
final filters = await NosmaiFlutter.instance.getLocalFilters();
await NosmaiFlutter.instance.applyFilter(filters[0].path);
```

### Cloud Filters

```dart
final cloudFilters = await NosmaiFlutter.instance.getCloudFilters();
final result = await NosmaiFlutter.instance.downloadCloudFilter(cloudFilters[0].id);
await NosmaiFlutter.instance.applyFilter(result['path']);
```

### Beauty Filters

```dart
await NosmaiFlutter.instance.applySkinSmoothing(5.0);
await NosmaiFlutter.instance.applyFaceSlimming(3.0);
await NosmaiFlutter.instance.applyEyeEnlargement(2.0);
await NosmaiFlutter.instance.removeAllFilters();
```

## How It Works

```
┌─────────────────────────────────┐
│      Flutter Application        │
└────────────┬────────────────────┘
             │
             │ getNativeHandle()
             ▼
┌─────────────────────────────────┐
│   NosmaiAgoraBridge Plugin      │
└────────────┬────────────────────┘
             │
        ┌────┴─────┐
        │          │
        ▼          ▼
┌──────────┐  ┌──────────┐
│ Android  │  │   iOS    │
│VideoRaw  │  │VideoRaw  │
│DataCtrl  │  │DataCtrl  │
└─────┬────┘  └────┬─────┘
      │            │
      └────┬───────┘
           ▼
    ┌─────────────┐
    │ Agora RTC + │
    │ Nosmai SDK  │
    └─────────────┘
```

1. `getNativeHandle()` creates native VideoRawDataController
2. VideoRawDataController intercepts Agora video frames
3. Frames are processed by Nosmai SDK (filters applied)
4. Processed frames are sent to remote users


## API Reference

### NosmaiAgoraBridge

| Method | Description | Returns |
|--------|-------------|---------|
| `getNativeHandle({required String agoraAppId})` | Get native handle for Agora integration | `Future<int>` |
| `initialize({required String agoraAppId})` | Convenience method - creates and initializes RtcEngine | `Future<RtcEngine>` |
| `disposeNative()` | Clean up native resources only | `Future<void>` |
| `dispose()` | Clean up RtcEngine and native resources | `Future<void>` |
| `engine` | Get current RtcEngine instance | `RtcEngine?` |
| `isInitialized` | Check if native bridge is initialized | `bool` |

## FAQ

**Q: Do I need to modify my existing Agora code?**
A: Minimal changes - just add `getNativeHandle()` and use the handle when creating RtcEngine.

**Q: Can I use all Nosmai SDK features?**
A: Yes! Use `NosmaiFlutter.instance` to access all Nosmai features.

**Q: Does this work with existing Agora features?**
A: Absolutely. You configure and use RtcEngine exactly as before.

**Q: What about performance?**
A: Native video processing is highly optimized with minimal performance impact.

## Important Notes

### Local Preview Configuration

Currently, for the local preview to display correctly, you need to configure the `VideoCanvas` with specific render and mirror settings:

```dart
AgoraVideoView(
  controller: VideoViewController(
    rtcEngine: _engine,
    canvas: const VideoCanvas(
      uid: 0,
      renderMode: RenderModeType.renderModeFit,
      mirrorMode: VideoMirrorModeType.videoMirrorModeDisabled,
    ),
  ),
),
```

### Known Issues

- **Android Beauty Filters**: Beauty filters are currently not working in live streaming on Android. We are actively working on fixing this issue and will release an update soon.

## Troubleshooting

### Filters not appearing

1. Ensure Nosmai SDK is initialized before calling `getNativeHandle()`
2. Verify filter paths are correct
3. Check Nosmai API key is valid

## License

MIT License - see LICENSE file for details.

## Credits

- Built with [Agora RTC Engine](https://www.agora.io/)
- Powered by [Nosmai Camera SDK](https://nosmai.com/)
