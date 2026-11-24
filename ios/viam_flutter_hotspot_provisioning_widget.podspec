#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint viam_flutter_hotspot_provisioning_widget.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'viam_flutter_hotspot_provisioning_widget'
  s.version          = '0.0.35'
  s.summary          = 'Native Wi-Fi hotspot connection support for the Viam hotspot provisioning widget.'
  s.description      = <<-DESC
Joins a machine's provisioning hotspot by SSID prefix, reads the current SSID and
disconnects, for the viam_flutter_hotspot_provisioning_widget Flutter package.
                       DESC
  s.homepage         = 'https://www.viam.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = 'Viam, Inc.'
  s.source           = { :path => '.' }
  s.source_files = 'viam_flutter_hotspot_provisioning_widget/Sources/viam_flutter_hotspot_provisioning_widget/**/*'
  s.dependency 'Flutter'
  s.frameworks = 'NetworkExtension', 'SystemConfiguration'
  s.platform = :ios, '13.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  # s.resource_bundles = {'viam_flutter_hotspot_provisioning_widget_privacy' => ['viam_flutter_hotspot_provisioning_widget/Sources/viam_flutter_hotspot_provisioning_widget/PrivacyInfo.xcprivacy']}
end
