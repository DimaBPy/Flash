package com.example.flash.handshake

import android.content.Context
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

data class ColorHandshake(
    val peerId: String,
    val displayColor: Int,
    val ip: String,
    val port: Int,
    val token: String,
    val fileCount: Int = 1
)

class CameraHandshakeManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val peerId = UUID.randomUUID().toString().substring(0, 8)
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    private val _colorHandshakeFlow = MutableSharedFlow<ColorHandshake>(extraBufferCapacity = 1)
    val colorHandshakeFlow: SharedFlow<ColorHandshake> = _colorHandshakeFlow.asSharedFlow()

    /**
     * Generate a unique, distinguishable color for this device.
     * Uses hue-based generation to ensure colors are well-spaced across the color wheel.
     */
    fun generateDisplayColor(): Int {
        val hue = (peerId.hashCode() and 0xFF).toFloat()
        val saturation = 0.9f  // High saturation for distinctness
        val value = 0.95f       // High brightness for camera visibility
        return Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }

    /**
     * Start advertising this device on the local network via mDNS.
     */
    fun startAdvertising(displayColor: Int, port: Int, token: String, fileCount: Int) {
        if (nsdManager == null) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "flash-$peerId"
            serviceType = "_flash-handshake._tcp"
            setPort(port)
            setAttribute("color", Integer.toHexString(displayColor))
            setAttribute("token", token)
            setAttribute("fileCount", fileCount.toString())
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    /**
     * Start discovering other Flash devices on the local network.
     */
    fun startDiscovery() {
        if (nsdManager == null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let {
                    if (it.serviceName != "flash-$peerId") {
                        resolveService(it)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }

        nsdManager.discoverServices("_flash-handshake._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (nsdManager == null) return

        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { resolved ->
                    val hostAddress = resolved.host?.hostAddress ?: return@let
                    val port = resolved.port
                    val color = resolved.attributes["color"]?.let {
                        Integer.parseUnsignedInt(it, 16)
                    } ?: Color.BLUE
                    val token = resolved.attributes["token"] ?: return@let
                    val fileCount = resolved.attributes["fileCount"]?.toIntOrNull() ?: 1

                    _colorHandshakeFlow.tryEmit(ColorHandshake(
                        peerId = resolved.serviceName,
                        displayColor = color,
                        ip = hostAddress,
                        port = port,
                        token = token,
                        fileCount = fileCount
                    ))
                }
            }
        }

        nsdManager.resolveService(serviceInfo, resolveListener)
    }

    fun stopAdvertising() {
        if (nsdManager != null && registrationListener != null) {
            nsdManager.unregisterService(registrationListener)
        }
    }

    fun stopDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            nsdManager.stopServiceDiscovery(discoveryListener)
        }
    }
}
