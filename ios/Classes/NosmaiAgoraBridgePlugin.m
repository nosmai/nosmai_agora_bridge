#import "NosmaiAgoraBridgePlugin.h"
#import "VideoRawDataController.h"

@interface NosmaiAgoraBridgePlugin()
@property(nonatomic, strong) VideoRawDataController* videoController;
@end

@implementation NosmaiAgoraBridgePlugin

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  FlutterMethodChannel* channel = [FlutterMethodChannel
      methodChannelWithName:@"nosmai_agora_bridge"
            binaryMessenger:[registrar messenger]];
  NosmaiAgoraBridgePlugin* instance = [[NosmaiAgoraBridgePlugin alloc] init];
  [registrar addMethodCallDelegate:instance channel:channel];
}

- (void)handleMethodCall:(FlutterMethodCall*)call result:(FlutterResult)result {
  if ([@"getPlatformVersion" isEqualToString:call.method]) {
    result([@"iOS " stringByAppendingString:[[UIDevice currentDevice] systemVersion]]);
  }
  else if ([@"native_init" isEqualToString:call.method]) {
    NSString* appId = call.arguments[@"appId"];
    if (appId != nil) {
      @try {
        self.videoController = [[VideoRawDataController alloc] initWith:appId];
        intptr_t nativeHandle = [self.videoController getNativeHandle];
        result(@(nativeHandle));
      } @catch (NSException *exception) {
        result([FlutterError errorWithCode:@"INIT_ERROR"
                                   message:[NSString stringWithFormat:@"Failed to initialize: %@", exception.reason]
                                   details:nil]);
      }
    } else {
      result([FlutterError errorWithCode:@"INVALID_ARGS"
                                 message:@"App ID is required"
                                 details:nil]);
    }
  }
  else if ([@"native_dispose" isEqualToString:call.method]) {
    @try {
      [self.videoController dispose];
      self.videoController = nil;
      result(@(YES));
    } @catch (NSException *exception) {
      result([FlutterError errorWithCode:@"DISPOSE_ERROR"
                                 message:[NSString stringWithFormat:@"Failed to dispose: %@", exception.reason]
                                 details:nil]);
    }
  }
  else {
    result(FlutterMethodNotImplemented);
  }
}

- (void)dealloc {
  [self.videoController dispose];
  self.videoController = nil;
}

@end
