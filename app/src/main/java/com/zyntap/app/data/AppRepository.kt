package com.zyntap.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

@Immutable
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

/** Lưu danh sách app đã chọn và tra cứu thông tin app trên máy. */
class AppRepository(context: Context) {
    private val ctx = context.applicationContext
    private val pm: PackageManager = ctx.packageManager
    private val prefs = ctx.getSharedPreferences("zyntap", Context.MODE_PRIVATE)

    fun loadPinned(): List<String> =
        prefs.getString(KEY_PINNED, "").orEmpty().split("\n").filter { it.isNotBlank() }

    fun savePinned(packages: List<String>) {
        prefs.edit().putString(KEY_PINNED, packages.joinToString("\n")).apply()
    }

    fun resolve(packageName: String): AppEntry? = try {
        val info = pm.getApplicationInfo(packageName, 0)
        AppEntry(
            packageName = packageName,
            label = pm.getApplicationLabel(info).toString(),
            icon = pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Tất cả app có biểu tượng mở được, trừ chính ZynTap. */
    fun launchable(): List<AppEntry> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .mapNotNull { resolve(it) }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(packageName: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val KEY_PINNED = "pinned_packages"
        const val ICON_PX = 128
    }
}
