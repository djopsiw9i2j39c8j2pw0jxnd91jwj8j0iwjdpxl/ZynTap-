package com.zyntap.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.RemoteInput
import com.zyntap.app.data.AdbLinkStore
import com.zyntap.app.data.WifiAdbPairing
import kotlinx.coroutines.runBlocking

/** Nhận mã 6 số gõ trực tiếp trên thông báo rồi ghép nối ở nền. */
class PairReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairNotifier.KEY_CODE)
            ?.toString()
            .orEmpty()
            .filter { it.isDigit() }
            .take(6)

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                PairNotifier.showBusy(app)
                val error = pair(app, code)
                if (error == null) {
                    AdbLinkStore.setPaired(app, true)
                    PairNotifier.showResult(app, ok = true, detail = "")
                } else {
                    PairNotifier.showResult(app, ok = false, detail = error)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Trả về null nếu ghép nối thành công, ngược lại trả về lý do lỗi. */
    private fun pair(context: Context, code: String): String? {
        if (code.length != 6) return "Mã phải đủ 6 số"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Cần Android 11 trở lên"
        val result = runBlocking { WifiAdbPairing(context).pair(code) }
        return result.exceptionOrNull()?.message ?: if (result.isSuccess) null else "Ghép nối thất bại"
    }
}
