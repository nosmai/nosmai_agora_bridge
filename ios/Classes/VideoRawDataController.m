#import <Foundation/Foundation.h>
#import "VideoRawDataController.h"
#import <AgoraRtcKit/AgoraRtcKit.h>
#import <CoreVideo/CoreVideo.h>
#import <objc/message.h>

// Dynamic runtime lookup for NosmaiFlutterPlugin (peer dependency)
static BOOL CallNosmaiProcessor(CVPixelBufferRef buffer, BOOL flip) {
    Class cls = NSClassFromString(@"NosmaiFlutterPlugin");
    SEL sel = @selector(processExternalPixelBuffer:shouldFlip:);
    if (cls && [cls respondsToSelector:sel]) {
        BOOL (*fn)(Class, SEL, CVPixelBufferRef, BOOL) =
            (BOOL (*)(Class, SEL, CVPixelBufferRef, BOOL))objc_msgSend;
        return fn(cls, sel, buffer, flip);
    }
    return NO;
}

@interface VideoRawDataController ()<AgoraRtcEngineDelegate, AgoraVideoFrameDelegate>

@property(nonatomic, strong) AgoraRtcEngineKit *agoraRtcEngine;
@property(nonatomic, assign) BOOL isFrontCamera;

@end

@implementation VideoRawDataController

- (instancetype)initWith:(NSString *) appId {
    self = [super init];
    if (self) {
        // Initialize camera state (same as Android)
        self.isFrontCamera = YES;

        AgoraRtcEngineConfig *config = [[AgoraRtcEngineConfig alloc] init];
        config.appId = appId;
        self.agoraRtcEngine = [AgoraRtcEngineKit sharedEngineWithConfig:config delegate:self];

        // ✅ Disable local video mirroring (same as Android)
        // Nosmai will handle un-mirroring for external frames
        // Local preview mirror is handled by Agora internally for front camera
        [self.agoraRtcEngine setLocalVideoMirrorMode:AgoraVideoMirrorModeDisabled];

        // ✅ Configure video encoder - disable mirror (same as Android)
        // Frames are already un-mirrored by Nosmai, send as-is to remote
        AgoraVideoEncoderConfiguration *encoderConfig = [[AgoraVideoEncoderConfiguration alloc] init];
        encoderConfig.mirrorMode = AgoraVideoMirrorModeDisabled;
        [self.agoraRtcEngine setVideoEncoderConfiguration:encoderConfig];

        NSLog(@"✅ iOS VideoRawDataController initialized - mirror disabled, Nosmai handles un-mirroring");

        [self.agoraRtcEngine setVideoFrameDelegate:self];
    }

    return self;
}

- (intptr_t)getNativeHandle {
    return (intptr_t)[self.agoraRtcEngine getNativeHandle];
}

- (void)switchCamera {
    // Toggle camera state (same as Android)
    self.isFrontCamera = !self.isFrontCamera;

    // Switch Agora's camera
    [self.agoraRtcEngine switchCamera];

    NSLog(@"📷 iOS Camera switched → isFrontCamera=%@ (Nosmai will %@)",
          self.isFrontCamera ? @"YES" : @"NO",
          self.isFrontCamera ? @"UN-mirror" : @"keep original");
}

- (void)dispose {
    [self.agoraRtcEngine setVideoFrameDelegate:NULL];
    [AgoraRtcEngineKit destroy];
}

// MARK: - AgoraVideoFrameDelegate
- (BOOL)onCaptureVideoFrame:(AgoraOutputVideoFrame *)videoFrame sourceType:(AgoraVideoSourceType)sourceType {
    // Type 12 = CVPixelBufferRef NV12
    // Type 13 = CVPixelBufferRef I420
    // Type 14 = CVPixelBufferRef BGRA
    if (videoFrame.type == 14) {
        CVPixelBufferRef pixelBuffer = videoFrame.pixelBuffer;
        if (pixelBuffer == NULL) {
            return NO;
        }

        // ✅ Use state variable (same as Android) - NOT sourceType!
        // shouldFlip parameter: YES = manually flip horizontally before Nosmai processing
        // Front camera: shouldFlip:YES to UN-mirror for remote users
        // Back camera: shouldFlip:NO to keep original orientation
        CallNosmaiProcessor(pixelBuffer, self.isFrontCamera);

        return YES;
    }
    return YES;
}

- (AgoraVideoFormat)getVideoFormatPreference {
    return AgoraVideoFormatCVPixelBGRA;
}

- (AgoraVideoFrameProcessMode)getVideoFrameProcessMode {
    return AgoraVideoFrameProcessModeReadWrite;
}

- (BOOL)getMirrorApplied {
    // ✅ Return NO (same as Android)
    // Front camera: Nosmai un-mirrors → frame is NOT mirrored anymore
    // Back camera: Nosmai keeps original → frame is NOT mirrored
    // Result: Remote users always see correct (non-mirrored) orientation
    return NO;
}

@end

