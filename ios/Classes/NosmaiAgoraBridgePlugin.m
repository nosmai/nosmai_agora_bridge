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
  // Dart has always invoked native_start_streaming / native_stop_streaming
  // (nosmai_agora_bridge_method_channel.dart:39, :50) and Android has always
  // handled them (NosmaiAgoraBridgePlugin.kt:50, :69). iOS had no branch for
  // either, so both calls fell through to FlutterMethodNotImplemented. Argument
  // names and error codes below match Android exactly.
  else if ([@"native_start_streaming" isEqualToString:call.method]) {
    VideoRawDataController* controller = self.videoController;
    if (controller == nil) {
      result([FlutterError errorWithCode:@"NOT_INITIALIZED"
                                 message:@"Call getNativeHandle before startStreaming"
                                 details:nil]);
      return;
    }
    NSString* channelName = call.arguments[@"channelName"];
    if (![channelName isKindOfClass:[NSString class]] || channelName.length == 0) {
      result([FlutterError errorWithCode:@"INVALID_ARGS"
                                 message:@"channelName is required"
                                 details:nil]);
      return;
    }
    NSString* token = call.arguments[@"token"];
    if (![token isKindOfClass:[NSString class]]) {
      token = nil;  // Dart sends an explicit null for tokenless channels
    }
    NSNumber* uid = call.arguments[@"uid"];
    @try {
      BOOL started = [controller startStreaming:token
                                        channel:channelName
                                            uid:(NSUInteger)[uid unsignedIntegerValue]];
      result(@(started));
    } @catch (NSException *exception) {
      result([FlutterError errorWithCode:@"START_STREAM_ERROR"
                                 message:[NSString stringWithFormat:@"Failed to start stream: %@", exception.reason]
                                 details:nil]);
    }
  }
  else if ([@"native_stop_streaming" isEqualToString:call.method]) {
    @try {
      VideoRawDataController* controller = self.videoController;
      // Android returns true when there is no controller (nothing to stop is
      // not a failure) — keep the same contract.
      result(@(controller == nil ? YES : [controller stopStreaming]));
    } @catch (NSException *exception) {
      result([FlutterError errorWithCode:@"STOP_STREAM_ERROR"
                                 message:[NSString stringWithFormat:@"Failed to stop stream: %@", exception.reason]
                                 details:nil]);
    }
  }
  else if ([@"notify_camera_switch" isEqualToString:call.method]) {
    @try {
      [self.videoController notifyCameraSwitch];
      result(@(YES));
    } @catch (NSException *exception) {
      result([FlutterError errorWithCode:@"CAMERA_SWITCH_ERROR"
                                 message:[NSString stringWithFormat:@"Failed to notify camera switch: %@", exception.reason]
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
