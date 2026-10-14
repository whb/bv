package dev.aaa1115910.bv.mobile.screen

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.mobile.activities.VideoPlayerActivity
import dev.aaa1115910.bv.mobile.component.videocard.UpSpaceVideoItem
import dev.aaa1115910.bv.viewmodel.user.UserSpaceViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSpaceScreen(
    modifier: Modifier = Modifier,
    userSpaceViewModel: UserSpaceViewModel = koinViewModel()
) {
    val context = LocalContext.current

    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(Unit) {
        val intent = (context as Activity).intent
        if (intent.hasExtra("mid")) {
            val mid = intent.getLongExtra("mid", 0)
            val name = intent.getStringExtra("name") ?: ""
            userSpaceViewModel.upMid = mid
            userSpaceViewModel.upName = name
            userSpaceViewModel.update()
        } else {
            context.finish()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = userSpaceViewModel.upName) },
                navigationIcon = {
                    IconButton(onClick = {
                        (context as Activity).finish()
                    }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        val topPadding = innerPadding.calculateTopPadding()
        when {
            // 加载中（含首次加载前的瞬间）
            userSpaceViewModel.loading || !userSpaceViewModel.loadedOnce -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(text = stringResource(R.string.user_space_loading))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f))
                    }
                }
            }
            // 加载失败：展示错误与重试按钮
            userSpaceViewModel.loadFailed -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPadding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                ) {
                    Text(
                        text = stringResource(R.string.user_space_load_failed),
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = { userSpaceViewModel.update() }) {
                        Text(text = stringResource(R.string.user_space_retry))
                    }
                }
            }
            // 加载完成但没有视频：展示空状态，不再白屏
            userSpaceViewModel.spaceVideos.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = stringResource(R.string.user_space_no_video))
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(top = topPadding)
                ) {
                    items(items = userSpaceViewModel.spaceVideos) { video ->
                        UpSpaceVideoItem(
                            spaceVideo = video,
                            onClick = {
                                VideoPlayerActivity.actionStart(
                                    context = context,
                                    aid = video.aid
                                )
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                }
            }
        }
    }
}
