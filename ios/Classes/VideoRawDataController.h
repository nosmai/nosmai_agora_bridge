#ifndef VideoRawDataController_h
#define VideoRawDataController_h

@interface VideoRawDataController : NSObject

- (instancetype)initWith:(NSString *) appId;
- (intptr_t)getNativeHandle;
- (void)switchCamera;
- (void)notifyCameraSwitch;  // Update camera state without physical switch
- (void)dispose;

@end

#endif /* VideoRawDataController_h */
