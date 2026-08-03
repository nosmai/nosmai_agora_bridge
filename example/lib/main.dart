import 'dart:io';

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
  final Set<int> _remoteUids = {};

  // Bundled filters, discovered at runtime by getLocalFilters().
  List<NosmaiFilter> _filters = [];
  String? _activeFilterPath;
  bool _loadingFilters = true;

  // ── Credentials ───────────────────────────────────────────────────────
  // Replace all three before running. Nothing here is committed with real
  // values on purpose.
  //
  // _appId / _channelName come from your Agora project (console.agora.io).
  // Leave _token EMPTY unless the project has a certificate enabled — the
  // Flutter SDK expresses "no token" as an empty string, and a stale token
  // fails the join with a bare -110.
  final String _appId = 'YOUR_AGORA_APP_ID';
  final String _token = '';
  final String _channelName = 'YOUR_CHANNEL_NAME';

  // Nosmai licence keys are PLATFORM-SPECIFIC and bound to your app's bundle
  // id — an Android key is rejected on iOS and vice versa.
  final String _nosmaiKey = Platform.isIOS
      ? 'YOUR_NOSMAI_IOS_KEY'
      : 'YOUR_NOSMAI_ANDROID_KEY';

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

    // ORDER IS LOAD-BEARING: getNativeHandle BEFORE NosmaiFlutter.initialize.
    //
    // getNativeHandle creates Agora's engine and stashes its EGL context as the
    // share context. The Nosmai GL context must be created AFTER that, so it
    // joins Agora's share group — a texture produced by Nosmai is otherwise
    // invisible to Agora's encoder and the remote side shows black.
    //
    // The example previously initialized Nosmai first, which silently produced
    // exactly that failure. tikshot's agora_service.dart documents the same
    // rule (resetNosmaiShareGroup: cleanup -> getNativeHandle -> initialize).
    final nativeHandle = await NosmaiAgoraBridge.getNativeHandle(
      agoraAppId: _appId,
    );

    // Now bring up Nosmai, which joins the share group established above.
    await NosmaiFlutter.initialize(_nosmaiKey);

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

    await _engine!.enableVideo();

    // Join and publish through the BRIDGE, not _engine.joinChannel.
    //
    // This is the whole point of the plugin. startStreaming joins the channel
    // AND switches Agora to the Nosmai-supplied texture, so the filtered frames
    // are what get published. Calling _engine.joinChannel directly (as this
    // example previously did) joins with Agora's OWN raw camera — the stream
    // works, but Nosmai is bypassed entirely and no filter is visible remotely,
    // which looks like "the bridge is broken" rather than a wiring mistake.
    //
    // Nosmai owns the camera in this model, so no _engine.startPreview() here.
    //
    // token: null (not '') — the bridge's API is nullable and forwards null for
    // a project with no certificate. tikshot does the same:
    //   startStreaming(token: (t != null && t.isNotEmpty) ? t : null)
    final ok = await NosmaiAgoraBridge.startStreaming(
      channelName: _channelName,
      token: _token.isNotEmpty ? _token : null,
      uid: 0,
    );
    if (!ok) {
      debugPrint('❌ NosmaiAgoraBridge.startStreaming returned false');
    }

    // Only after Nosmai is initialized — getLocalFilters needs the SDK up.
    await _loadFilters();
  }

  Future<void> _disposeAgora() async {
    // Stop the bridge FIRST: it owns the channel membership (startStreaming
    // joined it), and it must release the Agora texture helper before the
    // engine goes away. Tearing down in the other order leaves the helper
    // holding a share-group reference to a dead engine — the documented cause
    // of the 2nd-go-live frozen-frame → black failure.
    await NosmaiAgoraBridge.stopStreaming();
    await _engine?.leaveChannel();
    await _engine?.release();
    await NosmaiAgoraBridge.disposeNative();
  }

  /// Load the bundled filters once Nosmai is up.
  ///
  /// getLocalFilters() scans the app's bundled assets and decodes each preview,
  /// so it is deliberately called ONCE and cached rather than per tap.
  Future<void> _loadFilters() async {
    try {
      final filters = await NosmaiFlutter.instance.getLocalFilters();
      if (!mounted) return;
      setState(() {
        _filters = filters;
        _loadingFilters = false;
      });
      debugPrint('Loaded ${filters.length} local filters');
    } catch (e) {
      debugPrint('getLocalFilters failed: $e');
      if (mounted) setState(() => _loadingFilters = false);
    }
  }

  /// Apply one filter by path.
  ///
  /// applyFilter handles BOTH shader filters and AR effects — the SDK routes on
  /// the package's manifest type, so there is no separate applyEffect call to
  /// make here.
  Future<void> _applyFilter(NosmaiFilter filter) async {
    final ok = await NosmaiFlutter.instance.applyFilter(filter.path);
    if (!mounted) return;
    setState(() => _activeFilterPath = ok ? filter.path : null);
    if (!ok) debugPrint('applyFilter failed: ${filter.name}');
  }

  Future<void> _removeFilters() async {
    await NosmaiFlutter.instance.removeAllFilters();
    if (!mounted) return;
    setState(() => _activeFilterPath = null);
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
                  // LOCAL preview comes from NOSMAI, not Agora.
                  //
                  // Nosmai owns the camera in this model, so an AgoraVideoView
                  // with uid:0 (which this example used to show) renders
                  // Agora's own capture — i.e. the UNFILTERED camera, or
                  // nothing at all once Nosmai has the device. The filtered
                  // frames only exist inside Nosmai's pipeline.
                  child: const NosmaiCameraPreview(),
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

          // ── Filter strip ──
          // Horizontal list of every bundled filter. Tapping applies it live;
          // the stream keeps running, so the web viewer sees the change
          // immediately — which is the point of the test.
          Positioned(
            bottom: 110,
            left: 0,
            right: 0,
            child: SizedBox(
              height: 84,
              child: _loadingFilters
                  ? const Center(
                      child: SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    )
                  : _filters.isEmpty
                      ? const Center(
                          child: Text(
                            'No filters found',
                            style: TextStyle(color: Colors.white70),
                          ),
                        )
                      : ListView.separated(
                          scrollDirection: Axis.horizontal,
                          padding: const EdgeInsets.symmetric(horizontal: 12),
                          itemCount: _filters.length,
                          separatorBuilder: (_, __) => const SizedBox(width: 8),
                          itemBuilder: (context, i) {
                            final f = _filters[i];
                            final active = f.path == _activeFilterPath;
                            return GestureDetector(
                              onTap: () => _applyFilter(f),
                              child: Container(
                                width: 72,
                                decoration: BoxDecoration(
                                  color: Colors.black54,
                                  borderRadius: BorderRadius.circular(10),
                                  border: Border.all(
                                    color: active
                                        ? Colors.blueAccent
                                        : Colors.white24,
                                    width: active ? 2.5 : 1,
                                  ),
                                ),
                                padding: const EdgeInsets.all(4),
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Icon(
                                      // AR effects carry 3D/face assets and are
                                      // orders of magnitude larger than shader
                                      // filters, so the type is worth showing.
                                      f.filterCategory ==
                                              NosmaiFilterCategory.effect
                                          ? Icons.face_retouching_natural
                                          : Icons.auto_awesome,
                                      color: active
                                          ? Colors.blueAccent
                                          : Colors.white,
                                      size: 22,
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      f.displayName,
                                      maxLines: 2,
                                      overflow: TextOverflow.ellipsis,
                                      textAlign: TextAlign.center,
                                      style: TextStyle(
                                        fontSize: 9,
                                        color: active
                                            ? Colors.blueAccent
                                            : Colors.white,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
            ),
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
                  // Nosmai owns the camera, so switching must go through it —
                  // _engine.switchCamera() drives Agora's own capture, which is
                  // not the source being published here.
                  onPressed: () async {
                    await NosmaiFlutter.instance.switchCamera();
                    await NosmaiAgoraBridge.notifyCameraSwitch();
                  },
                  backgroundColor: Colors.white,
                  child: const Icon(Icons.cameraswitch, color: Colors.black),
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
