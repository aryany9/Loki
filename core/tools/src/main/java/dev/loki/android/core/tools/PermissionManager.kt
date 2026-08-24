package dev.loki.android.core.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class PermissionState {
    GRANTED,
    REQUESTABLE,
    PERMANENTLY_DENIED
}

/**
 * Manages runtime Android permission checking, resolution, and state queries across Loki.
 */
class PermissionManager {

    fun checkPermission(context: Context, permission: String): PermissionState {
        val isGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (isGranted) {
            return PermissionState.GRANTED
        }

        if (context is Activity) {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(context, permission)
            return if (shouldShowRationale) {
                PermissionState.REQUESTABLE
            } else {
                PermissionState.REQUESTABLE
            }
        }

        return PermissionState.REQUESTABLE
    }

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun arePermissionsGranted(context: Context, permissions: List<String>): Boolean {
        return permissions.all { isPermissionGranted(context, it) }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
