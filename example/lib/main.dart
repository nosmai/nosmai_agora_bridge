import 'package:flutter/material.dart';
import 'package:agora_rtc_engine/agora_rtc_engine.dart';
import 'package:nosmai_agora_bridge/nosmai_agora_bridge.dart';
import 'package:nosmai_camera_sdk/nosmai_camera_sdk.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Nosmai Agora Bridge Example',
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
  RtcEngine? _engine;
  bool _isJoined = false;
  Set<int> _remoteUids = {};

  final String _appId = 'YOUR_AGORA_APP_ID';
  final String _token = 'YOUR_AGORA_TOKEN';
  final String _channelName = 'test_channel';
  final String _nosmaiKey = 'YOUR_NOSMAI_KEY';

  @override
  void initState() {
    super.initState();
    _initAgora();
  }

  @override
  void dispose() {
    _disposeAgora();
    super.dispose();
  }

  Future<void> _initAgora() async {
    await [Permission.camera, Permission.microphone].request();

    // Initialize Nosmai SDK
    await NosmaiFlutter.initialize(_nosmaiKey);

    // Get native handle for Nosmai integration
    final nativeHandle = await NosmaiAgoraBridge.getNativeHandle(
      agoraAppId: _appId,
    );

    // Create Agora engine with shared native handle
    _engine = createAgoraRtcEngine(sharedNativeHandle: nativeHandle);

    // Initialize engine
    await _engine!.initialize(RtcEngineContext(
      appId: _appId,
      channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
    ));

    // Register event handlers
    _engine!.registerEventHandler(
      RtcEngineEventHandler(
        onJoinChannelSuccess: (connection, elapsed) {
          setState(() => _isJoined = true);
          debugPrint('Joined channel: ${connection.channelId}');
        },
        onUserJoined: (connection, remoteUid, elapsed) {
          setState(() => _remoteUids.add(remoteUid));
          debugPrint('User joined: $remoteUid');
        },
        onUserOffline: (connection, remoteUid, reason) {
          setState(() => _remoteUids.remove(remoteUid));
          debugPrint('User left: $remoteUid');
        },
      ),
    );

    // Enable video and start preview
    await _engine!.enableVideo();
    await _engine!.startPreview();

    // Join channel
    await _engine!.joinChannel(
      token: _token,
      channelId: _channelName,
      uid: 0,
      options: const ChannelMediaOptions(
        clientRoleType: ClientRoleType.clientRoleBroadcaster,
        channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
      ),
    );
  }

  Future<void> _disposeAgora() async {
    await _engine?.leaveChannel();
    await _engine?.release();
    await NosmaiAgoraBridge.disposeNative();
  }

  Future<void> _applyFilter() async {
    final filters = await NosmaiFlutter.instance.getLocalFilters();

    if (filters.isNotEmpty) {
      await NosmaiFlutter.instance.applyFilter(filters[0].path);

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Applied: ${filters[0].displayName}')),
        );
      }
    }
  }

  Future<void> _removeFilters() async {
    await NosmaiFlutter.instance.removeAllFilters();

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Filters removed')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Nosmai + Agora Live Stream'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Stack(
        children: [
          Column(
            children: [
              Expanded(
                child: Container(
                  color: Colors.black,
                  child: _isJoined
                      ? AgoraVideoView(
                          controller: VideoViewController(
                            rtcEngine: _engine!,
                            canvas: const VideoCanvas(uid: 0),
                          ),
                        )
                      : const Center(
                          child: CircularProgressIndicator(),
                        ),
                ),
              ),

              if (_remoteUids.isNotEmpty)
                SizedBox(
                  height: 200,
                  child: AgoraVideoView(
                    controller: VideoViewController.remote(
                      rtcEngine: _engine!,
                      canvas: VideoCanvas(uid: _remoteUids.first),
                      connection: RtcConnection(channelId: _channelName),
                    ),
                  ),
                ),
            ],
          ),

          Positioned(
            bottom: 30,
            left: 0,
            right: 0,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                FloatingActionButton(
                  heroTag: 'leave',
                  onPressed: () async {
                    await _engine?.leaveChannel();
                    if (mounted) Navigator.pop(context);
                  },
                  backgroundColor: Colors.red,
                  child: const Icon(Icons.call_end),
                ),

                FloatingActionButton(
                  heroTag: 'switch',
                  onPressed: () => _engine?.switchCamera(),
                  backgroundColor: Colors.white,
                  child: const Icon(Icons.cameraswitch, color: Colors.black),
                ),

                FloatingActionButton(
                  heroTag: 'filter',
                  onPressed: _applyFilter,
                  backgroundColor: Colors.blue,
                  child: const Icon(Icons.filter),
                ),

                FloatingActionButton(
                  heroTag: 'remove',
                  onPressed: _removeFilters,
                  backgroundColor: Colors.orange,
                  child: const Icon(Icons.filter_none),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
