package com.zyntap.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zyntap.app.data.DebugLog
import com.zyntap.app.data.UpdateLog

/** Sau khi cập nhật app, ghi log cập nhật rồi tự bật lại menu nổi nếu trước đó đang bật (không cần mở lại app). */
class OverlayRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            DebugLog.init(context)
            UpdateLog.check(context)
            OverlayService.restoreIfWanted(context)
        }
    }
}
