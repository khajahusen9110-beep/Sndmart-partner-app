package com.example.util

import android.os.Build

object DeviceUtils {
    /**
     * Determines whether the app is running in an Android Emulator / Virtual Device
     * to safely bypass operations requiring physical Google Play Services registration.
     */
    fun isEmulator(): Boolean {
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()

        return fingerprint.startsWith("generic")
                || fingerprint.startsWith("unknown")
                || model.contains("google_sdk")
                || model.contains("emulator")
                || model.contains("android sdk built for")
                || model.contains("sdk_gphone")
                || manufacturer.contains("genymotion")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || product.contains("sdk")
                || product.contains("google_sdk")
                || product.contains("emulator")
                || (brand.startsWith("generic") && device.startsWith("generic"))
    }
}
