import Flutter
import NetworkExtension
import SystemConfiguration.CaptiveNetwork
import UIKit

/// Joins, inspects and leaves the provisioning hotspot for the Dart
/// `PluginWifiConnectService`.
public class ViamFlutterHotspotProvisioningWidgetPlugin: NSObject, FlutterPlugin {
  static let channelName = "viam_flutter_hotspot_provisioning_widget/wifi"

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: channelName, binaryMessenger: registrar.messenger())
    let instance = ViamFlutterHotspotProvisioningWidgetPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let reply = SingleReply(result)
    switch call.method {
    case "getSSID":
      fetchCurrentSSID { ssid in reply.send(ssid) }

    case "securePrefixConnect":
      guard let args = call.arguments as? [String: Any],
        let prefix = args["ssid"] as? String,
        let password = args["password"] as? String,
        let isWep = args["isWep"] as? Bool
      else {
        reply.send(
          FlutterError(
            code: "missingArgs",
            message: "securePrefixConnect requires ssid, password and isWep",
            details: nil))
        return
      }
      let saveNetwork = args["saveNetwork"] as? Bool ?? false
      securePrefixConnect(prefix: prefix, password: password, isWep: isWep, saveNetwork: saveNetwork, reply: reply)

    case "disconnect":
      disconnect(reply: reply)

    default:
      reply.send(FlutterMethodNotImplemented)
    }
  }

  private func securePrefixConnect(prefix: String, password: String, isWep: Bool, saveNetwork: Bool, reply: SingleReply) {
    let configuration = NEHotspotConfiguration(ssidPrefix: prefix, passphrase: password, isWEP: isWep)
    configuration.joinOnce = !saveNetwork

    NEHotspotConfigurationManager.shared.apply(configuration) { [weak self] error in
      if let error = error as NSError? {
        if error.domain == NEHotspotConfigurationErrorDomain
          && error.code == NEHotspotConfigurationError.alreadyAssociated.rawValue
        {
          reply.send(true)
        } else {
          reply.send(false)
        }
        return
      }
      guard let self = self else {
        reply.send(false)
        return
      }
      // apply() can report success without actually joining, so confirm the
      // phone is now on a network with the requested prefix.
      self.fetchCurrentSSID { ssid in
        reply.send(ssid?.hasPrefix(prefix) ?? false)
      }
    }
  }

  private func disconnect(reply: SingleReply) {
    fetchCurrentSSID { ssid in
      guard let ssid = ssid else {
        reply.send(false)
        return
      }
      NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
      reply.send(true)
    }
  }

  private func fetchCurrentSSID(completion: @escaping (String?) -> Void) {
    if #available(iOS 14.0, *) {
      NEHotspotNetwork.fetchCurrent { network in
        completion(network?.ssid)
      }
    } else {
      completion(legacyCurrentSSID())
    }
  }

  @available(iOS, deprecated: 14.0)
  private func legacyCurrentSSID() -> String? {
    guard let interfaces = CNCopySupportedInterfaces() as? [String] else {
      return nil
    }
    for interface in interfaces {
      if let info = CNCopyCurrentNetworkInfo(interface as CFString) as? [String: Any],
        let ssid = info[kCNNetworkInfoKeySSID as String] as? String
      {
        return ssid
      }
    }
    return nil
  }
}

/// Wraps a `FlutterResult` so it is invoked exactly once, on the main thread,
/// whichever thread the NetworkExtension callbacks arrive on.
private final class SingleReply {
  private let result: FlutterResult
  private let lock = NSLock()
  private var sent = false

  init(_ result: @escaping FlutterResult) {
    self.result = result
  }

  func send(_ value: Any?) {
    lock.lock()
    let alreadySent = sent
    sent = true
    lock.unlock()
    if alreadySent {
      return
    }
    if Thread.isMainThread {
      result(value)
    } else {
      DispatchQueue.main.async { self.result(value) }
    }
  }
}
