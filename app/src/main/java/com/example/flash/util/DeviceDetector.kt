package com.example.flash.util

import android.os.Build

object DeviceDetector {
    fun isHyperOSDevice(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val device = Build.DEVICE.lowercase()

        return brand.contains("xiaomi") ||
               manufacturer.contains("xiaomi") ||
               device.contains("xiaomi") ||
               Build.DISPLAY.lowercase().contains("hyperos")
    }

    fun getDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
    }
}
