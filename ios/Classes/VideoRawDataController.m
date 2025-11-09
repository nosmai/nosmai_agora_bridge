#import <Foundation/Foundation.h>
#import "VideoRawDataController.h"
#import <AgoraRtcKit/AgoraRtcKit.h>
#import <CoreVideo/CoreVideo.h>
#import <objc/message.h>

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
        AgoraRtcEngineConfig *config = [[AgoraRtcEngineConfig alloc] init];
        config.appId = appId;
        self.agoraRtcEngine = [AgoraRtcEngineKit sharedEngineWithConfig:config delegate:self];

        [self.agoraRtcEngine setLocalVideoMirrorMode:AgoraVideoMirrorModeDisabled];

        AgoraVideoEncoderConfiguration *encoderConfig = [[AgoraVideoEncoderConfiguration alloc] init];
        encoderConfig.mirrorMode = AgoraVideoMirrorModeDisabled;
        [self.agoraRtcEngine setVideoEncoderConfiguration:encoderConfig];

        self.isFrontCamera = YES;

        NSLog(@"VideoRawDataController initialized");

        [self.agoraRtcEngine setVideoFrameDelegate:self];
    }

    return self;
}

- (intptr_t)getNativeHandle {
    return (intptr_t)[self.agoraRtcEngine getNativeHandle];
}

- (void)switchCamera {
    [self.agoraRtcEngine switchCamera];
    self.isFrontCamera = !self.isFrontCamera;
}

- (void)notifyCameraSwitch {
    self.isFrontCamera = !self.isFrontCamera;
}

- (void)dispose {
    [self.agoraRtcEngine setVideoFrameDelegate:NULL];
    [AgoraRtcEngineKit destroy];
}

- (BOOL)onCaptureVideoFrame:(AgoraOutputVideoFrame *)videoFrame sourceType:(AgoraVideoSourceType)sourceType {
    // Type 12 = CVPixelBufferRef NV12
    // Type 13 = CVPixelBufferRef I420
    // Type 14 = CVPixelBufferRef BGRA
    if (videoFrame.type == 14) {
        CVPixelBufferRef pixelBuffer = videoFrame.pixelBuffer;
        if (pixelBuffer == NULL) {
            return NO;
        }

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
    return NO;
}

@end

