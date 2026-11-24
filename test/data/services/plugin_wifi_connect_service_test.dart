import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:viam_flutter_hotspot_provisioning_widget/viam_flutter_hotspot_provisioning_widget.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('viam_flutter_hotspot_provisioning_widget/wifi');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late List<MethodCall> calls;
  late PluginWifiConnectService service;

  void respondWith(Object? Function(MethodCall call) handler) {
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return handler(call);
    });
  }

  setUp(() {
    calls = <MethodCall>[];
    // The default constructor must use the same channel name as the native plugins.
    service = PluginWifiConnectService();
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  group('getCurrentSSID', () {
    test('calls getSSID and passes the SSID through unchanged', () async {
      respondWith((_) => '"viam-setup-1234"');

      expect(await service.getCurrentSSID(), '"viam-setup-1234"');
      expect(calls.single.method, 'getSSID');
      expect(calls.single.arguments, isNull);
    });

    test('passes <unknown ssid> through unchanged', () async {
      respondWith((_) => '<unknown ssid>');

      expect(await service.getCurrentSSID(), '<unknown ssid>');
    });

    test('returns null when the platform has no SSID', () async {
      respondWith((_) => null);

      expect(await service.getCurrentSSID(), isNull);
    });
  });

  group('connectToSecureNetworkByPrefix', () {
    test('calls securePrefixConnect with the prefix as ssid and all flags', () async {
      respondWith((_) => true);

      final result = await service.connectToSecureNetworkByPrefix(
        prefix: 'viam-setup',
        password: 'viamsetup',
        isWep: false,
        isWpa3: true,
        saveNetwork: true,
      );

      expect(result, isTrue);
      expect(calls.single.method, 'securePrefixConnect');
      expect(calls.single.arguments, <String, dynamic>{
        'ssid': 'viam-setup',
        'password': 'viamsetup',
        'saveNetwork': true,
        'isWep': false,
        'isWpa3': true,
      });
    });

    test('returns false when the platform returns false', () async {
      respondWith((_) => false);

      final result = await service.connectToSecureNetworkByPrefix(
        prefix: 'viam-setup',
        password: 'viamsetup',
        isWep: false,
        isWpa3: false,
        saveNetwork: false,
      );

      expect(result, isFalse);
    });

    test('returns false when the platform returns null', () async {
      respondWith((_) => null);

      final result = await service.connectToSecureNetworkByPrefix(
        prefix: 'viam-setup',
        password: 'viamsetup',
        isWep: false,
        isWpa3: false,
        saveNetwork: false,
      );

      expect(result, isFalse);
    });

    test('surfaces platform errors as PlatformException', () async {
      respondWith((_) => throw PlatformException(code: 'unsupported'));

      expect(
        service.connectToSecureNetworkByPrefix(prefix: 'viam-setup', password: 'viamsetup', isWep: true, isWpa3: false, saveNetwork: false),
        throwsA(isA<PlatformException>().having((e) => e.code, 'code', 'unsupported')),
      );
    });
  });

  group('disconnect', () {
    test('calls disconnect with no arguments and returns the result', () async {
      respondWith((_) => true);

      expect(await service.disconnect(), isTrue);
      expect(calls.single.method, 'disconnect');
      expect(calls.single.arguments, isNull);
    });

    test('returns false when the platform returns null', () async {
      respondWith((_) => null);

      expect(await service.disconnect(), isFalse);
    });
  });

  test('uses an injected channel instead of the default one', () async {
    const injected = MethodChannel('test/injected');
    messenger.setMockMethodCallHandler(injected, (call) async => 'injected-ssid');
    addTearDown(() => messenger.setMockMethodCallHandler(injected, null));

    expect(await PluginWifiConnectService(channel: injected).getCurrentSSID(), 'injected-ssid');
  });
}
