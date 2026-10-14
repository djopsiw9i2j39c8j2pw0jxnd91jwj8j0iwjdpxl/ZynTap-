package com.zyntap.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Sau khi cập nhật app, tự bật lại menu nổi nếu trước đó đang bật (không cần mở lại app). */
class OverlayRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            OverlayService.restoreIfWanted(context)
        }
    }
}
