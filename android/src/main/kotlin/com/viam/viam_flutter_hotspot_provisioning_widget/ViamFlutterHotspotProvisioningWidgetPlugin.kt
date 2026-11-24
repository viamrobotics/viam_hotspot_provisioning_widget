package com.viam.viam_flutter_hotspot_provisioning_widget

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import androidx.annotation.RequiresApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Joins, inspects and leaves the provisioning hotspot for the Dart `PluginWifiConnectService`.
 *
 * On API 29+ the hotspot is requested with [WifiNetworkSpecifier] and the whole process is bound to
 * it, so that traffic (the gRPC calls to the machine) goes over the hotspot even though it has no
 * internet. Below API 29 the legacy [WifiConfiguration] path is used.
 */
class ViamFlutterHotspotProvisioningWidgetPlugin : FlutterPlugin, MethodCallHandler {
    private var channel: MethodChannel? = null
    private var context: Context? = null

    // API 29+: the active network request. Keeping it registered keeps us on the hotspot.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingConnectReply: Reply? = null

    // API < 29: the id returned by WifiManager.addNetwork, needed to disconnect.
    private var legacyNetworkId: Int? = null

    // Every receiver we register, so none outlives the engine.
    private val registeredReceivers = mutableSetOf<BroadcastReceiver>()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME).also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null

        val ctx = context
        if (ctx != null) {
            registeredReceivers.toList().forEach { unregisterReceiver(ctx, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                releaseNetworkRequest(ctx)
            }
        }
        registeredReceivers.clear()
        pendingConnectReply = null
        context = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val ctx = context
        if (ctx == null) {
            result.error("noContext", "Plugin is not attached to an engine", null)
            return
        }
        val reply = Reply(result)
        when (call.method) {
            "getSSID" -> reply.success(currentSsid(ctx))
            "securePrefixConnect" -> securePrefixConnect(ctx, call, reply)
            "disconnect" -> disconnect(ctx, reply)
            else -> result.notImplemented()
        }
    }

    private fun securePrefixConnect(ctx: Context, call: MethodCall, reply: Reply) {
        val prefix = call.argument<String>("ssid")
        val password = call.argument<String>("password")
        val isWep = call.argument<Boolean>("isWep")
        val isWpa3 = call.argument<Boolean>("isWpa3") ?: false

        if (prefix == null || password == null || isWep == null) {
            reply.error("missingArgs", "securePrefixConnect requires ssid, password and isWep", null)
            return
        }
        if (isWep) {
            reply.error("unsupported", "WEP networks are not supported on Android", null)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsidPattern(PatternMatcher(prefix, PatternMatcher.PATTERN_PREFIX))
                .apply { if (isWpa3) setWpa3Passphrase(password) else setWpa2Passphrase(password) }
                .build()
            requestNetwork(ctx, specifier, reply)
        } else {
            legacyConnectByPrefix(ctx, prefix, legacyWpaConfig(password), reply)
        }
    }

    private fun disconnect(ctx: Context, reply: Reply) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            reply.success(releaseNetworkRequest(ctx))
        } else {
            legacyDisconnect(ctx, reply)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun currentSsid(ctx: Context): String? = wifiManager(ctx).connectionInfo?.ssid

    // region API 29+

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestNetwork(ctx: Context, specifier: WifiNetworkSpecifier, reply: Reply) {
        val connectivityManager = connectivityManager(ctx)

        // A previous request is still registered: drop it before asking for a new one. The Dart
        // layer normally disconnects first, so this only happens on misuse.
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        pendingConnectReply?.success(false)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // Route this process's traffic over the hotspot, which has no internet.
                connectivityManager.bindProcessToNetwork(network)
                reply.success(true)
                if (pendingConnectReply === reply) pendingConnectReply = null
                // Do not unregister here: that would drop the hotspot connection.
            }

            override fun onUnavailable() {
                super.onUnavailable()
                reply.success(false)
                if (pendingConnectReply === reply) pendingConnectReply = null
            }
        }
        networkCallback = callback
        pendingConnectReply = reply
        connectivityManager.requestNetwork(request, callback, Handler(Looper.getMainLooper()))
    }

    /** Unregisters the hotspot request and unbinds the process. Returns false if there was none. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun releaseNetworkRequest(ctx: Context): Boolean {
        val callback = networkCallback ?: return false
        val connectivityManager = connectivityManager(ctx)
        connectivityManager.unregisterNetworkCallback(callback)
        connectivityManager.bindProcessToNetwork(null)
        networkCallback = null
        pendingConnectReply?.success(false)
        pendingConnectReply = null
        return true
    }

    // endregion

    // region API < 29

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun legacyConnectByPrefix(ctx: Context, prefix: String, config: WifiConfiguration, reply: Reply) {
        val wifiManager = wifiManager(ctx)

        fun onScanDone() {
            val ssid = wifiManager.scanResults
                .filter { it.SSID.startsWith(prefix) }
                .maxByOrNull { it.level }
                ?.SSID
            if (ssid == null) {
                reply.success(false)
            } else {
                config.SSID = "\"" + ssid + "\""
                legacyConnect(ctx, config, reply)
            }
        }

        val scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unregisterReceiver(ctx, this)
                onScanDone()
            }
        }
        registerReceiver(ctx, scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        if (!wifiManager.startScan()) {
            // Scan throttled or refused: use whatever results are cached.
            unregisterReceiver(ctx, scanReceiver)
            onScanDone()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun legacyConnect(ctx: Context, config: WifiConfiguration, reply: Reply) {
        val wifiManager = wifiManager(ctx)
        val networkId = wifiManager.addNetwork(config)
        if (networkId == -1) {
            reply.success(false)
            return
        }
        wifiManager.saveConfiguration()

        val stateReceiver = object : BroadcastReceiver() {
            var count = 0

            override fun onReceive(context: Context, intent: Intent) {
                count++
                val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                if (info == null || !info.isConnected) return
                if (info.extraInfo == config.SSID || currentSsid(ctx) == config.SSID) {
                    unregisterReceiver(ctx, this)
                    reply.success(true)
                } else if (count > 1) {
                    // The first broadcast may be for the network we were already on; ignore it.
                    unregisterReceiver(ctx, this)
                    reply.success(false)
                }
            }
        }
        registerReceiver(ctx, stateReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))

        wifiManager.enableNetwork(networkId, true)
        legacyNetworkId = networkId
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun legacyDisconnect(ctx: Context, reply: Reply) {
        val networkId = legacyNetworkId
        if (networkId == null) {
            reply.success(false)
            return
        }
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                if (info != null && !info.isConnected) {
                    unregisterReceiver(ctx, this)
                    reply.success(true)
                }
            }
        }
        registerReceiver(ctx, stateReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))

        // Remove the network to behave as closely as possible to the API 29+ path.
        val wifiManager = wifiManager(ctx)
        wifiManager.removeNetwork(networkId)
        wifiManager.reconnect()
        legacyNetworkId = null
    }

    @Suppress("DEPRECATION")
    private fun legacyWpaConfig(password: String): WifiConfiguration = WifiConfiguration().apply {
        allowedProtocols.set(WifiConfiguration.Protocol.RSN)
        allowedProtocols.set(WifiConfiguration.Protocol.WPA)

        allowedAuthAlgorithms.clear()

        allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
        allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)

        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)

        preSharedKey = "\"" + password + "\""
        status = WifiConfiguration.Status.ENABLED

        allowedKeyManagement.clear()
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
    }

    // endregion

    // Only system broadcasts (scan results, network state) are registered for. Those are delivered to
    // non-exported receivers, and below API 33 the export flag does not exist. Lint cannot see the
    // filter through this helper, hence the suppression.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver(ctx: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        registeredReceivers.add(receiver)
    }

    private fun unregisterReceiver(ctx: Context, receiver: BroadcastReceiver) {
        if (!registeredReceivers.remove(receiver)) return
        try {
            ctx.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered by the system; nothing to do.
        }
    }

    private fun wifiManager(ctx: Context): WifiManager =
        ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private fun connectivityManager(ctx: Context): ConnectivityManager =
        ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Replies to one method call at most once; later replies are dropped. */
    private class Reply(private val result: Result) {
        private var sent = false

        fun success(value: Any?) {
            if (sent) return
            sent = true
            result.success(value)
        }

        fun error(code: String, message: String?, details: Any?) {
            if (sent) return
            sent = true
            result.error(code, message, details)
        }
    }

    companion object {
        const val CHANNEL_NAME = "viam_flutter_hotspot_provisioning_widget/wifi"
    }
}
