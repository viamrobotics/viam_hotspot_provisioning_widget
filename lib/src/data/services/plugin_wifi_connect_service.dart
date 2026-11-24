part of '../../../../../viam_flutter_hotspot_provisioning_widget.dart';

/// Joins and leaves the machine's provisioning hotspot.
///
/// Backed by this package's own native code (Swift on iOS, Kotlin on Android)
/// over a method channel. The name is kept from when it wrapped the
/// `plugin_wifi_connect` package.
class PluginWifiConnectService {
  /// [channel] is for tests; by default the service talks to this package's native plugin.
  PluginWifiConnectService({@visibleForTesting MethodChannel? channel}) : _channel = channel ?? _defaultChannel;

  static const MethodChannel _defaultChannel = MethodChannel('viam_flutter_hotspot_provisioning_widget/wifi');

  final MethodChannel _channel;

  /// The SSID of the current Wi-Fi network, as the platform reports it.
  ///
  /// On Android it is wrapped in double quotes, or is `<unknown ssid>` without
  /// location permission.
  Future<String?> getCurrentSSID() async {
    return await _channel.invokeMethod<String>('getSSID');
  }

  Future<bool> connectToSecureNetworkByPrefix({
    required String prefix,
    required String password,
    required bool isWep,
    required bool isWpa3,
    required bool saveNetwork,
  }) async {
    final result = await _channel.invokeMethod<bool>('securePrefixConnect', <String, dynamic>{
      'ssid': prefix,
      'password': password,
      'saveNetwork': saveNetwork,
      'isWep': isWep,
      'isWpa3': isWpa3,
    });
    return result ?? false;
  }

  Future<bool> disconnect() async {
    final result = await _channel.invokeMethod<bool>('disconnect');
    return result ?? false;
  }
}
