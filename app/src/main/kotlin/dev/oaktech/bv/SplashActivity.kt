package dev.oaktech.bv

import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.oaktech.bv.mobile.activities.MainActivity as MobileMainActivity
import dev.oaktech.bv.tv.activities.MainActivity as TvMainActivity

/**
 * 单一启动入口（dispatcher）。
 * 同时声明 LAUNCHER 与 LEANBACK_LAUNCHER，无论桌面走哪种 category 都由它接收，
 * 再按设备类型转发到 mobile / tv 的 MainActivity，避免"先经 mobile 再跳 tv"的中转。
 */
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = if (isTvDevice()) TvMainActivity::class.java else MobileMainActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }

    private fun isTvDevice(): Boolean {
        val pm = packageManager
        // Android TV / Google TV
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature("android.software.leanback_only")) return true
        // 部分国产 TV 固件走 UI 模式
        val uiMode = getSystemService(UiModeManager::class.java)?.currentModeType
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
        // 旧 API 的电视特征
        if (pm.hasSystemFeature("android.hardware.type.television")) return true
        return false
    }
}
