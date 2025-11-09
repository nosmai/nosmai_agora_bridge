#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint nosmai_agora_bridge.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'nosmai_agora_bridge'
  s.version          = '0.0.1'
  s.summary          = 'Nosmai filters integration for Agora RTC Engine'
  s.description      = <<-DESC
Easy integration of Nosmai filters with Agora RTC Engine for Flutter.
Process live video streams with real-time filters without writing native code.
                       DESC
  s.homepage         = 'https://github.com/nosmai/nosmai_agora_bridge'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Nosmai' => 'admin@nosmai.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*.{h,m}'
  s.public_header_files = 'Classes/**/*.h'
  s.dependency 'Flutter'
  s.dependency 'AgoraRtcEngine_iOS'
  s.platform = :ios, '13.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386'
  }
end
