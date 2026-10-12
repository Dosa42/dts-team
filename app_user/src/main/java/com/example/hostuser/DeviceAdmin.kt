package com.example.hostuser

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * DeviceAdminReceiver enabling Device Owner / Kiosk administration rights
 * on the custom tablet via:
 * adb shell dpm set-device-owner com.example.hostuser/.DeviceAdmin
 */
class DeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
