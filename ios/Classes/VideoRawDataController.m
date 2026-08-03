#import <Foundation/Foundation.h>
#import "VideoRawDataController.h"
#import <AgoraRtcKit/AgoraRtcKit.h>
#import <CoreVideo/CoreVideo.h>
#import <CoreMedia/CoreMedia.h>
#import <objc/message.h>

// ARCHITECTURE NOTE
//
// This file used to run the INVERTED model: Agora owned the capture device and
// handed each captured frame to Nosmai via -onCaptureVideoFrame: /
// +processExternalPixelBuffer:shouldFlip:. That was structurally broken for two
// reasons, so it has been removed rather than finished:
//   1. It forced the SDK into NosmaiProcessingModeOffscreen, whose setter calls
//      stopProcessing internally and tears down the AVCaptureSession — preview
//      and streaming could therefore never be live at the same time.
//   2. It blocked Agora's capture thread on a dispatch_semaphore for up to 50ms
//      per frame while waiting for the filtered result.
//
// The model below matches the shipped Android bridge: Nosmai owns the camera and
// PUSHES filtered frames into Agora as an external video source.

// Encoder parameters — MUST stay identical to the Android bridge
// (VideoRawDataController.kt:24-27, :293-306). Divergence here shows up as a
// different aspect/quality between the two platforms for the same broadcast.
static const NSInteger kStreamWidth = 720;
static const NSInteger kStreamHeight = 1280;
static const NSInteger kStreamBitrateKbps = 2500;
static const NSInteger kStreamMinBitrateKbps = 1200;

// AgoraVideoFrame.format value for a CVPixelBufferRef carried in `textureBuf`
// (AgoraObjects.h:1792 — "12: iOS texture (CVPixelBufferRef)").
static const NSInteger kAgoraVideoFormatCVPixelBuffer = 12;

#pragma mark - Nosmai plugin reflection

// This bridge does NOT link the Nosmai framework (nosmai_agora_bridge.podspec
// depends only on Flutter + AgoraRtcEngine_iOS). The camera plugin lives in a
// separate pod, so every call into it goes through runtime reflection — the same
// contract the previous implementation used.
static Class NosmaiPluginClass(void) {
    return NSClassFromString(@"NosmaiFlutterPlugin");
}

// Arms Nosmai's live-frame output: Nosmai keeps ownership of the camera and
// pushes every filtered frame to us. Declared in
// camsdk_v2/ios/Classes/NosmaiFlutterPlugin.h (+setLiveStreamFrameCallback:).
static BOOL ArmNosmaiLiveFrames(void (^callback)(CVPixelBufferRef, double)) {
    Class cls = NosmaiPluginClass();
    SEL sel = @selector(setLiveStreamFrameCallback:);
    if (!cls || ![cls respondsToSelector:sel]) {
        return NO;
    }
    void (*fn)(Class, SEL, id) = (void (*)(Class, SEL, id))objc_msgSend;
    fn(cls, sel, callback);
    return YES;
}

static void DisarmNosmaiLiveFrames(void) {
    Class cls = NosmaiPluginClass();
    SEL sel = @selector(clearLiveStreamFrameCallback);
    if (cls && [cls respondsToSelector:sel]) {
        void (*fn)(Class, SEL) = (void (*)(Class, SEL))objc_msgSend;
        fn(cls, sel);
    }
}

@interface VideoRawDataController ()<AgoraRtcEngineDelegate>

@property(nonatomic, strong) AgoraRtcEngineKit *agoraRtcEngine;
@property(nonatomic, assign) BOOL isFrontCamera;
// Mirrors the Android controller's streamingRequested / inChannel / frameCount
// state (VideoRawDataController.kt:35-37).
@property(nonatomic, assign) BOOL streamingRequested;
@property(nonatomic, assign) BOOL inChannel;
@property(nonatomic, assign) NSInteger frameCount;

@end

@implementation VideoRawDataController

- (instancetype)initWith:(NSString *) appId {
    self = [super init];
    if (self) {
        AgoraRtcEngineConfig *config = [[AgoraRtcEngineConfig alloc] init];
        config.appId = appId;
        self.agoraRtcEngine = [AgoraRtcEngineKit sharedEngineWithConfig:config delegate:self];

        [self.agoraRtcEngine enableVideo];
        [self.agoraRtcEngine setLocalVideoMirrorMode:AgoraVideoMirrorModeDisabled];

        self.isFrontCamera = YES;
        self.streamingRequested = NO;
        self.inChannel = NO;
        self.frameCount = 0;

        [self configureEncoder];

        NSLog(@"VideoRawDataController initialized (Nosmai-owns-camera push model)");
    }

    return self;
}

// Previously this applied a default-constructed AgoraVideoEncoderConfiguration
// with only mirrorMode set, leaving dimensions/frameRate/bitrate/orientation at
// whatever a freshly allocated object yields — which can override Agora's own
// defaults rather than inherit them. Now it is an explicit 1:1 copy of Android.
- (void)configureEncoder {
    AgoraVideoEncoderConfiguration *encoderConfig =
        [[AgoraVideoEncoderConfiguration alloc]
             initWithSize:CGSizeMake(kStreamWidth, kStreamHeight)
                frameRate:AgoraVideoFrameRateFps30
                  bitrate:kStreamBitrateKbps
          orientationMode:AgoraVideoOutputOrientationModeFixedPortrait
               mirrorMode:AgoraVideoMirrorModeDisabled];
    encoderConfig.minBitrate = kStreamMinBitrateKbps;
    [self.agoraRtcEngine setVideoEncoderConfiguration:encoderConfig];
    NSLog(@"Agora encoder: %ldx%ld %ldkbps min=%ld",
          (long)kStreamWidth, (long)kStreamHeight,
          (long)kStreamBitrateKbps, (long)kStreamMinBitrateKbps);
}

- (intptr_t)getNativeHandle {
    return (intptr_t)[self.agoraRtcEngine getNativeHandle];
}

- (void)switchCamera {
    // Camera ownership belongs to the Nosmai preview in this model — Agora must
    // not drive the capture device. Matches Android's no-op switchCamera()
    // (VideoRawDataController.kt:190-192); we only track which lens is active.
    self.isFrontCamera = !self.isFrontCamera;
}

- (void)notifyCameraSwitch {
    self.isFrontCamera = !self.isFrontCamera;
}

#pragma mark - Streaming

- (BOOL)startStreaming:(NSString *)token
               channel:(NSString *)channelName
                   uid:(NSUInteger)uid {
    AgoraRtcEngineKit *engine = self.agoraRtcEngine;
    if (!engine) return NO;
    if (channelName.length == 0) return NO;

    // RE-ENTRANCY GUARD — same rationale as Android
    // (VideoRawDataController.kt:97-109): the Dart caller is driven by a socket
    // event the server can emit more than once. Without this, a second call
    // re-ran setup while the first join was still in flight, Agora rejected it
    // with -17, and the failure path then tore down the attempt that was about
    // to succeed — channel joined, producer off, black remote, no error.
    if (self.streamingRequested) {
        NSLog(@"startStreaming ignored: already streaming/joining channel=%@", channelName);
        return YES;
    }

    self.streamingRequested = YES;
    self.frameCount = 0;

    // Register as the video producer BEFORE joining, so the custom track exists
    // when the channel options ask to publish it.
    // useTexture:NO — we push CVPixelBuffers (AgoraVideoFrame format 12). iOS has
    // no equivalent of Android's EGL share-group texture path.
    [engine setExternalVideoSource:YES
                        useTexture:NO
                        sourceType:AgoraExternalVideoSourceTypeVideoFrame];
    [engine setClientRole:AgoraClientRoleBroadcaster];

    if (![self armNosmaiFrameCallback]) {
        // The camera plugin is absent or too old to expose the push API. Fail
        // loudly instead of joining a channel that would publish nothing.
        self.streamingRequested = NO;
        NSLog(@"❌ startStreaming: NosmaiFlutterPlugin does not expose "
              @"+setLiveStreamFrameCallback: — cannot publish filtered frames");
        return NO;
    }

    AgoraRtcChannelMediaOptions *options = [[AgoraRtcChannelMediaOptions alloc] init];
    options.clientRoleType = AgoraClientRoleBroadcaster;
    options.channelProfile = AgoraChannelProfileLiveBroadcasting;
    options.publishCameraTrack = NO;        // Nosmai owns the camera, not Agora
    options.publishCustomVideoTrack = YES;  // …and we push its filtered output
    options.publishCustomAudioTrack = YES;
    options.autoSubscribeVideo = YES;
    options.autoSubscribeAudio = YES;

    NSString *safeToken = (token.length > 0) ? token : nil;
    int result = [engine joinChannelByToken:safeToken
                                  channelId:channelName
                                        uid:uid
                               mediaOptions:options
                                joinSuccess:nil];
    if (result != 0) {
        self.streamingRequested = NO;
        DisarmNosmaiLiveFrames();
        NSLog(@"joinChannel failed result=%d", result);
        return NO;
    }
    return YES;
}

- (BOOL)stopStreaming {
    self.streamingRequested = NO;
    DisarmNosmaiLiveFrames();

    AgoraRtcEngineKit *engine = self.agoraRtcEngine;
    if (!engine) return YES;

    // Disarm the external source so a reused engine (the app keeps the engine
    // alive across streams) re-arms cleanly next time instead of inheriting a
    // stale registration. Mirrors Android stopStreaming (kt:164-175).
    [engine setExternalVideoSource:NO
                        useTexture:NO
                        sourceType:AgoraExternalVideoSourceTypeVideoFrame];

    int result = [engine leaveChannel:nil];
    self.inChannel = NO;
    return result == 0;
}

- (BOOL)armNosmaiFrameCallback {
    __weak typeof(self) weakSelf = self;
    return ArmNosmaiLiveFrames(^(CVPixelBufferRef pixelBuffer, double timestamp) {
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf) return;
        [strongSelf pushFilteredBuffer:pixelBuffer timestamp:timestamp];
    });
}

- (void)pushFilteredBuffer:(CVPixelBufferRef)pixelBuffer timestamp:(double)timestamp {
    if (!pixelBuffer) return;

    // Gate on streamingRequested ONLY — deliberately not on inChannel.
    // inChannel is set exclusively from -rtcEngine:didJoinChannel: (:299 below).
    // Once the Flutter side builds its engine via
    // createAgoraRtcEngine(sharedNativeHandle:), the Dart/Iris layer attaches its
    // own delegate to this same native engine, and whether THIS ObjC delegate
    // still receives didJoinChannel is not knowable from code. If it does not,
    // inChannel stays NO forever and every frame is silently dropped — a joined
    // channel with a permanently black remote and no error anywhere.
    // streamingRequested is owned by this class's own start/stop path (set :143,
    // cleared :176/:194/:205), so it is immune to delegate delivery.
    if (!self.streamingRequested) return;

    AgoraRtcEngineKit *engine = self.agoraRtcEngine;
    if (!engine) return;

    // BUFFER LIFETIME: pushed synchronously on the SDK's frame-output thread. The
    // producer (nosmai_recording_sink.cc:596-599) does CVPixelBufferRetain →
    // callback(…) → CVPixelBufferRelease around this call, on a POOLED buffer
    // whose recycling is owned by RetireCompletedOutputs via pending_outputs_.
    // Whether -pushExternalVideoFrame consumes the buffer synchronously or
    // retains it for the encoder is UNVERIFIED. If it retains asynchronously, the
    // pool could recycle a buffer still queued in the encoder, giving intermittent
    // block corruption on the remote. We therefore retain across the push: this is
    // a refcount bump, not a copy, so it is cheap insurance on the hot path.
    CVPixelBufferRetain(pixelBuffer);

    size_t bufferWidth = CVPixelBufferGetWidth(pixelBuffer);
    size_t bufferHeight = CVPixelBufferGetHeight(pixelBuffer);

    AgoraVideoFrame *frame = [[AgoraVideoFrame alloc] init];
    frame.format = kAgoraVideoFormatCVPixelBuffer;
    frame.textureBuf = pixelBuffer;
    frame.time = CMTimeMakeWithSeconds(timestamp, 1000);

    // ROTATION — expected to evaluate to 0, because the source is already
    // PORTRAIT. The session preset names a 1280x720 FORMAT, but AVFoundation
    // rotates it: NosmaiSDK.mm:5708 sets
    // `param.videoOrientation = AVCaptureVideoOrientationPortrait`, applied to the
    // data-output connection at VideoCapturer.m:245. So buffers arrive upright at
    // 720x1280 and this ternary yields 0 into the FIXED_PORTRAIT 720x1280 encoder,
    // which is correct. (Do NOT "fix" this to a hardcoded 90 — that would rotate
    // already-correct portrait frames into landscape.)
    //
    // Front-camera mirroring is likewise already baked in by hardware
    // (VideoCapturer.m:240-243 sets videoMirrored = YES on the front connection),
    // which is why no bridge-side mirroring exists. That also makes the 90-vs-270
    // question moot: rotation is 0 on BOTH lenses.
    //
    // Kept as a dimension-derived expression rather than a literal 0 so it stays
    // correct if the SDK ever delivers landscape. The telemetry below prints the
    // actual size and rotation — verify it reads 720x1280 rotation=0 on device.
    frame.rotation = (bufferWidth > bufferHeight) ? 90 : 0;

    // videoTrackId 0 = the default custom video track, i.e. the one
    // ChannelMediaOptions.publishCustomVideoTrack publishes. The single-argument
    // -pushExternalVideoFrame: is deprecated in this Agora version.
    [engine pushExternalVideoFrame:frame videoTrackId:0];

    // Balances the retain above. If Agora retained the buffer for its encoder it
    // now holds its own reference; if it consumed it synchronously this is the
    // last release and the pool reclaims it as before.
    CVPixelBufferRelease(pixelBuffer);

    self.frameCount++;
    if (self.frameCount % 90 == 0) {
        NSLog(@"Pushed CVPixelBuffer frames=%ld size=%zux%zu rotation=%d",
              (long)self.frameCount, bufferWidth, bufferHeight, frame.rotation);
    }
}

- (void)dispose {
    @try {
        [self stopStreaming];
    } @catch (NSException *exception) {
        NSLog(@"dispose: stopStreaming threw: %@", exception.reason);
    }

    self.agoraRtcEngine = nil;
    [AgoraRtcEngineKit destroy];
}

#pragma mark - AgoraRtcEngineDelegate

- (void)rtcEngine:(AgoraRtcEngineKit *)engine
    didJoinChannel:(NSString *)channel
           withUid:(NSUInteger)uid
           elapsed:(NSInteger)elapsed {
    self.inChannel = YES;
    self.frameCount = 0;
    NSLog(@"Agora joined channel=%@ uid=%lu", channel, (unsigned long)uid);
}

- (void)rtcEngine:(AgoraRtcEngineKit *)engine didLeaveChannelWithStats:(AgoraChannelStats *)stats {
    self.inChannel = NO;
    // Only disarm when the app actually asked to stop. A leave callback can
    // arrive while a stream is still wanted (e.g. a rejected duplicate join
    // leaves a channel it never really held); disarming unconditionally there
    // would silence the producer for the stream that IS running.
    // Same guard as Android (VideoRawDataController.kt:54-61).
    if (!self.streamingRequested) {
        DisarmNosmaiLiveFrames();
    }
    NSLog(@"Agora left channel (streamingRequested=%d)", self.streamingRequested);
}

- (void)rtcEngine:(AgoraRtcEngineKit *)engine didOccurError:(AgoraErrorCode)errorCode {
    NSLog(@"Agora error=%ld", (long)errorCode);
}

@end
