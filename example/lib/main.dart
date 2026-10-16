import 'dart:async';
import 'dart:io';

import 'package:agora_rtc_engine/agora_rtc_engine.dart';
import 'package:flutter/material.dart';
import 'package:nosmai_agora_bridge/nosmai_agora_bridge.dart';
import 'package:nosmai_effects_sdk/nosmai_effects_sdk.dart';
import 'package:permission_handler/permission_handler.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Nosmai Agora Bridge',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const LiveStreamScreen(),
    );
  }
}

class LiveStreamScreen extends StatefulWidget {
  const LiveStreamScreen({super.key});

  @override
  State<LiveStreamScreen> createState() => _LiveStreamScreenState();
}

class _LiveStreamScreenState extends State<LiveStreamScreen> {
  static const _agoraAppId = 'YOUR_AGORA_APP_ID';
  static const _agoraToken = 'YOUR_AGORA_TOKEN';
  static const _channelName = 'test_channel';
  static const _nosmaiLicenseKey = 'YOUR_NOSMAI_LICENSE_KEY';

  RtcEngine? _engine;
  final Set<int> _remoteUids = <int>{};
  bool _ready = false;
  bool _joined = false;
  bool _streamStartRequested = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_initialize());
  }

  Future<void> _initialize() async {
    try {
      final permissions = await <Permission>[
        Permission.camera,
        Permission.microphone,
      ].request();
      if (permissions.values.any((status) => !status.isGranted)) {
        throw StateError('Camera and microphone permissions are required.');
      }

      if (Platform.isAndroid) {
        await _createAgoraEngine();
      }

      final initialized = await NosmaiFlutter.initialize(_nosmaiLicenseKey);
      if (!initialized) {
        throw StateError('Nosmai SDK initialization failed.');
      }

      if (Platform.isIOS) {
        await _createAgoraEngine();
        await _engine!.enableVideo();
        await _engine!.startPreview();
        await _engine!.joinChannel(
          token: _agoraToken,
          channelId: _channelName,
          uid: 0,
          options: const ChannelMediaOptions(
            clientRoleType: ClientRoleType.clientRoleBroadcaster,
            channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
          ),
        );
      }

      if (mounted) setState(() => _ready = true);
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  Future<void> _createAgoraEngine() async {
    final nativeHandle = await NosmaiAgoraBridge.getNativeHandle(
      agoraAppId: _agoraAppId,
    );
    if (nativeHandle == 0) {
      throw StateError('Agora native bridge initialization failed.');
    }

    final engine = createAgoraRtcEngine(sharedNativeHandle: nativeHandle);
    await engine.initialize(
      const RtcEngineContext(
        appId: _agoraAppId,
        channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
      ),
    );
    engine.registerEventHandler(
      RtcEngineEventHandler(
        onJoinChannelSuccess: (connection, elapsed) {
          if (mounted) setState(() => _joined = true);
        },
        onLeaveChannel: (connection, stats) {
          if (mounted) setState(() => _joined = false);
        },
        onUserJoined: (connection, remoteUid, elapsed) {
          if (mounted) setState(() => _remoteUids.add(remoteUid));
        },
        onUserOffline: (connection, remoteUid, reason) {
          if (mounted) setState(() => _remoteUids.remove(remoteUid));
        },
        onError: (errorCode, message) {
          if (mounted) setState(() => _error = 'Agora $errorCode: $message');
        },
      ),
    );
    _engine = engine;
  }

  Future<void> _onAndroidPreviewReady() async {
    if (!Platform.isAndroid || _streamStartRequested) return;
    _streamStartRequested = true;
    try {
      final accepted = await NosmaiAgoraBridge.startStreaming(
        channelName: _channelName,
        token: _agoraToken,
        uid: 0,
      );
      if (!accepted) {
        throw StateError('Agora rejected the stream start request.');
      }
    } catch (error) {
      _streamStartRequested = false;
      if (mounted) setState(() => _error = error.toString());
    }
  }

  Future<void> _applyFirstFilter() async {
    try {
      final filters = await NosmaiFlutter.instance.getLocalFilters();
      if (filters.isEmpty) throw StateError('No local filters are available.');
      await NosmaiFlutter.instance.applyEffect(filters.first.path);
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  Future<void> _switchCamera() async {
    if (Platform.isAndroid) {
      await NosmaiFlutter.instance.switchCamera();
      return;
    }
    await _engine?.switchCamera();
    await NosmaiAgoraBridge.notifyCameraSwitch();
  }

  Future<void> _shutdown() async {
    try {
      if (Platform.isAndroid) {
        await NosmaiAgoraBridge.stopStreaming();
      } else {
        await _engine?.leaveChannel();
      }
      await _engine?.release();
      await NosmaiAgoraBridge.disposeNative();
    } catch (_) {
      // The operating system will release any remaining camera resources.
    }
  }

  @override
  void dispose() {
    unawaited(_shutdown());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Nosmai + Agora')),
      body: Stack(
        fit: StackFit.expand,
        children: <Widget>[
          if (_error != null)
            Center(child: Text(_error!, textAlign: TextAlign.center))
          else if (!_ready)
            const Center(child: CircularProgressIndicator())
          else if (Platform.isAndroid)
            NosmaiCameraPreview(
              onInitialized: _onAndroidPreviewReady,
              onError: (message) => setState(() => _error = message),
            )
          else
            AgoraVideoView(
              controller: VideoViewController(
                rtcEngine: _engine!,
                canvas: const VideoCanvas(uid: 0),
              ),
            ),
          if (_remoteUids.isNotEmpty && _engine != null)
            Positioned(
              right: 16,
              top: 16,
              width: 120,
              height: 180,
              child: AgoraVideoView(
                controller: VideoViewController.remote(
                  rtcEngine: _engine!,
                  canvas: VideoCanvas(uid: _remoteUids.first),
                  connection: const RtcConnection(channelId: _channelName),
                ),
              ),
            ),
          if (_ready)
            Positioned(
              left: 16,
              right: 16,
              bottom: 24,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: <Widget>[
                  FloatingActionButton(
                    heroTag: 'switch',
                    onPressed: _switchCamera,
                    child: const Icon(Icons.cameraswitch),
                  ),
                  FloatingActionButton(
                    heroTag: 'filter',
                    onPressed: _applyFirstFilter,
                    child: const Icon(Icons.auto_awesome),
                  ),
                  FloatingActionButton(
                    heroTag: 'clear',
                    onPressed: NosmaiFlutter.instance.removeAllFilters,
                    child: const Icon(Icons.filter_none),
                  ),
                ],
              ),
            ),
          if (_ready)
            Positioned(
              left: 16,
              top: 16,
              child: Chip(label: Text(_joined ? 'Live' : 'Connecting')),
            ),
        ],
      ),
    );
  }
}
