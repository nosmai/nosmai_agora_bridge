#ifndef VideoRawDataController_h
#define VideoRawDataController_h

#import <Foundation/Foundation.h>

@interface VideoRawDataController : NSObject

- (instancetype)initWith:(NSString *) appId;
- (intptr_t)getNativeHandle;
- (void)switchCamera;
- (void)notifyCameraSwitch;  // Update camera state without physical switch

// Nosmai owns the camera and PUSHES filtered frames into Agora as an external
// video source. Mirrors the Android bridge (VideoRawDataController.kt:93/:160).
- (BOOL)startStreaming:(NSString *)token
               channel:(NSString *)channelName
                   uid:(NSUInteger)uid;
- (BOOL)stopStreaming;

- (void)dispose;

@end

#endif /* VideoRawDataController_h */
