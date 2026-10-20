package dev.oaktech.bv

import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.oaktech.bv.mobile.activities.MainActivity as MobileMainActivity
import dev.oaktech.bv.tv.activities.MainActivity as TvMainActivity

/**
 * 单一启动入口（dispatcher）。
 * 同时声明 LAUNCHER 与 LEANBACK_LAUNCHER，无论桌面走哪种 category 都由它接收，
 * 再按设备类型转发到 mobile / tv 的 MainActivity。
 *
 * 开屏（logo）由本 Activity 用 Compose 自绘：
 * - 不调用 installSplashScreen：API < 31 上 compat splash 会在窗口上盖一层视图，
 *   覆盖 Compose 内容，且 adaptive icon 的 foreground 作为 splash icon 在旧 API 上
 *   渲染不可靠；API 31+ 上系统 splash 仍会基于主题自动显示，无需手动 install。
 * - setContent 显示 Compose 开屏界面（暗色背景 + 粉色 logo），跨 API 版本行为一致；
 * - 延迟转发：让窗口先绘制（Compose 开屏显示），再启动 MainActivity；
 * - 直到目标 MainActivity 真正 onResumed 后才 finish。
 */
class SplashActivity : ComponentActivity() {

    @Volatile
    private var finished = false

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            Log.d("SplashActivity", "onActivityResumed: ${activity.javaClass.simpleName}, finished=$finished")
            if (activity is MobileMainActivity || activity is TvMainActivity) {
                if (!finished) {
                    finished = true
                    finish()
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("SplashActivity", "onCreate: start")
        super.onCreate(savedInstanceState)
        Log.d("SplashActivity", "onCreate: super done")

        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        Log.d("SplashActivity", "onCreate: registerCallbacks done")

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(160.dp)
                )
            }
        }
        Log.d("SplashActivity", "onCreate: setContent done")

        // 延迟转发：让窗口先绘制（Compose 开屏显示），再启动 MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            val target = if (isTvDevice()) TvMainActivity::class.java else MobileMainActivity::class.java
            Log.d("SplashActivity", "delayed: isTvDevice=${isTvDevice()}, target=$target")
            startActivity(Intent(this, target))
        }, 200)
        Log.d("SplashActivity", "onCreate: postDelayed done")
    }

    override fun onDestroy() {
        Log.d("SplashActivity", "onDestroy")
        super.onDestroy()
        application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
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
