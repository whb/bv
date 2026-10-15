package dev.oaktech.bv.viewmodel.user

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oaktech.biliapi.http.entity.AuthFailureException
import dev.oaktech.biliapi.repositories.ToViewRepository
import dev.oaktech.bv.BVApp
import dev.oaktech.bv.BuildConfig
import dev.oaktech.bv.R
import dev.oaktech.bv.entity.carddata.VideoCardData
import dev.oaktech.bv.repository.UserRepository
import dev.oaktech.bv.util.Prefs
import dev.oaktech.bv.util.addWithMainContext
import dev.oaktech.bv.util.fInfo
import dev.oaktech.bv.util.fWarn
import dev.oaktech.bv.util.formatHourMinSec
import dev.oaktech.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class ToViewViewModel(
    private val userRepository: UserRepository,
    private val ToViewRepository: ToViewRepository
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    var histories = mutableStateListOf<VideoCardData>()
    var noMore by mutableStateOf(false)

    private var cursor = 0L
    private var updating = false

    fun update() {
        viewModelScope.launch(Dispatchers.IO) {
            updateToView()
        }
    }

    private suspend fun updateToView(context: Context = BVApp.context) {
        if (updating || noMore) return
        logger.fInfo { "Updating histories with params [cursor=$cursor, apiType=${Prefs.apiType}]" }
        updating = true
        runCatching {
            val data = ToViewRepository.getToView(
                cursor = cursor,
                preferApiType = Prefs.apiType
            )

            data.data.forEach { ToViewItem ->
                histories.addWithMainContext(
                    VideoCardData(
                        avid = ToViewItem.oid,
                        title = ToViewItem.title,
                        cover = ToViewItem.cover,
                        upName = ToViewItem.author,
                        timeString = if (ToViewItem.progress == -1) context.getString(R.string.play_time_finish)
                        else context.getString(
                            R.string.play_time_history,
                            (ToViewItem.progress * 1000L).formatHourMinSec(),
                            (ToViewItem.duration * 1000L).formatHourMinSec()
                        )
                    )
                )
            }
            //update cursor
            cursor = data.cursor
            logger.fInfo { "Update toview cursor: [cursor=$cursor]" }
            logger.fInfo { "Update histories success" }
            if (cursor == 0L) {
                withContext(Dispatchers.Main) { noMore = true }
                logger.fInfo { "No more toview" }
            }
        }.onFailure {
            logger.fWarn { "Update histories failed: ${it.stackTraceToString()}" }
            when (it) {
                is AuthFailureException -> {
                    withContext(Dispatchers.Main) {
                        BVApp.context.getString(R.string.exception_auth_failure)
                            .toast(BVApp.context)
                    }
                    logger.fInfo { "User auth failure" }
                    if (!BuildConfig.DEBUG) userRepository.logout()
                }

                else -> {}
            }
        }
        updating = false
    }
}