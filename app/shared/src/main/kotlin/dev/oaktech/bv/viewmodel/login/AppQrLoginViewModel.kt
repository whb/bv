package dev.oaktech.bv.viewmodel.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oaktech.biliapi.entity.login.QrLoginState
import dev.oaktech.biliapi.repositories.LoginRepository
import dev.oaktech.bv.BVApp
import dev.oaktech.bv.entity.AuthData
import dev.oaktech.bv.repository.UserRepository
import dev.oaktech.bv.util.Prefs
import dev.oaktech.bv.util.fError
import dev.oaktech.bv.util.fInfo
import dev.oaktech.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class AppQrLoginViewModel(
    private val userRepository: UserRepository,
    private val loginRepository: LoginRepository
) : ViewModel() {
    var state by mutableStateOf(QrLoginState.Ready)
    private val logger = KotlinLogging.logger { }
    var loginUrl by mutableStateOf("")
    private var key = ""

    // 串行轮询任务：同一时刻最多一个 poll 请求在飞，避免晚到的 Expired 覆盖已处理的 Success
    private var checkJob: Job? = null

    // 终态保护：Success/Expired 处理完成后，后续 poll 结果一律忽略
    @Volatile
    private var terminalReached = false

    fun requestQRCode() {
        state = QrLoginState.Ready
        terminalReached = false
        checkJob?.cancel()
        logger.fInfo { "Request login qr code" }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                withContext(Dispatchers.Main) { state = QrLoginState.RequestingQRCode }
                val qrLoginData = loginRepository.requestAppQrLogin()
                loginUrl = qrLoginData.url
                key = qrLoginData.key
                logger.fInfo { "Get login request code url" }
                logger.info { qrLoginData.url }
                // 串行循环替代周期 Timer：上一次 check 完成后才发起下一次，
                // 杜绝 poll 请求重叠（重叠时成功 poll 会消费 auth_code，
                // 使在飞的旧 poll 返回 86038 过期，晚到的 Expired 会把 Success 状态覆盖掉）
                // 循环靠 terminalReached 退出，绝不在 checkLoginResult 内部取消自身协程，
                // 否则 Success 分支里正在执行的 addUser 会在挂起点被中断，导致"显示成功但未登录"。
                checkJob = viewModelScope.launch {
                    while (!terminalReached) {
                        delay(1000)
                        checkLoginResult()
                    }
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    it.message?.toast(BVApp.context)
                    state = QrLoginState.Error
                }
                logger.fError { "Get login request code url failed: ${it.stackTraceToString()}" }
                checkJob?.cancel()
            }
        }
    }

    fun cancelCheckLoginResultTimer() {
        checkJob?.cancel()
    }

    private suspend fun checkLoginResult() {
        if (terminalReached) return
        logger.fInfo { "Check for login result" }
        runCatching {
            val qrLoginResult = loginRepository.checkAppQrLoginState(key)
            // 终态保护：结果回来前如果已经登录成功（或已过期），丢弃本次结果
            if (terminalReached) return@runCatching
            withContext(Dispatchers.Main) { state = qrLoginResult.state }
            when (qrLoginResult.state) {
                QrLoginState.WaitingForScan -> {
                    logger.fInfo { "Waiting to scan" }
                }

                QrLoginState.WaitingForConfirm -> {
                    logger.fInfo { "Waiting to confirm" }
                }

                QrLoginState.Expired -> {
                    logger.fInfo { "QR expired" }
                    // 仅置终态标志，由 while(!terminalReached) 自然退出循环，
                    // 禁止在此处 checkJob?.cancel()（会取消正在执行本函数的协程）。
                    terminalReached = true
                }

                QrLoginState.Success -> {
                    logger.fInfo { "Login success" }
                    // 注意：必须先完整执行 addUser，再置终态/退出循环。
                    // 绝不可在此调用 checkJob?.cancel()——checkJob 就是当前协程，
                    // 自取消会使下面的 userRepository.addUser（含 Room 写入与登录态持久化）
                    // 在挂起点被中断，造成"界面显示成功、实际未登录"的回归。
                    val authData = AuthData(
                        uid = qrLoginResult.cookies!!.dedeUserId,
                        uidCkMd5 = qrLoginResult.cookies!!.dedeUserIdCkMd5,
                        sid = qrLoginResult.cookies!!.sid,
                        biliJct = qrLoginResult.cookies!!.biliJct,
                        sessData = qrLoginResult.cookies!!.sessData,
                        tokenExpiredData = qrLoginResult.cookies!!.expiredDate.time,
                        accessToken = qrLoginResult.accessToken!!,
                        refreshToken = qrLoginResult.refreshToken!!
                    )

                    // BlacklistUtil.checkUid(Prefs.uid)  // [已停用黑名单功能 2026-10-18]
                    userRepository.addUser(authData)

                    // addUser 完整执行完毕后再标记终态，确保登录态已落盘。
                    terminalReached = true
                }

                else -> {
                    logger.fInfo { "This state should not be here: ${qrLoginResult.state}" }
                }
            }
        }.onFailure {
            if (it is CancellationException) {
                logger.fInfo { "Timer job cancelled" }
                return@onFailure
            }
            if (terminalReached) return@onFailure
            withContext(Dispatchers.Main) {
                it.message?.toast(BVApp.context)
                state = QrLoginState.Error
            }
            logger.fError { "Check qr state failed: ${it.stackTraceToString()}" }
        }
    }
}
