package dev.oaktech.bv.tv.screens.user

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.oaktech.bv.R
import dev.oaktech.bv.tv.component.LoadingTip
import dev.oaktech.bv.tv.activities.video.UpInfoActivity
import dev.oaktech.bv.tv.util.PinyinSearch
import dev.oaktech.bv.ui.theme.BVTheme
import dev.oaktech.bv.util.requestFocus
import dev.oaktech.bv.viewmodel.user.FollowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

@Composable
fun FollowScreen(
    modifier: Modifier = Modifier,
    followViewModel: FollowViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var currentIndex by remember { mutableIntStateOf(0) }
    var keyword by remember { mutableStateOf("") }
    val showLargeTitle by remember { derivedStateOf { currentIndex < 3 } }
    val titleFontSize by animateFloatAsState(
        targetValue = if (showLargeTitle) 48f else 24f,
        label = "title font size"
    )

    // 检索文本块（昵称原文 + 拼音全拼 + 首字母 + 签名），列表就绪后在后台线程预构建，
    // 避免首次输入时在主线程加载拼音字典造成卡顿
    var searchBlobs by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(followViewModel.followedUsers.toList()) {
        val users = followViewModel.followedUsers.toList()
        if (users.isEmpty()) {
            searchBlobs = emptyMap()
        } else {
            searchBlobs = withContext(Dispatchers.Default) {
                users.associate { user ->
                    user.mid to (PinyinSearch.buildSearchBlob(user.name) + user.sign.lowercase())
                }
            }
        }
    }

    val filteredUsers by remember {
        derivedStateOf {
            val kw = PinyinSearch.normalize(keyword)
            if (kw.isEmpty()) {
                followViewModel.followedUsers.toList()
            } else {
                followViewModel.followedUsers.filter { user ->
                    val blob = searchBlobs[user.mid]
                    if (blob != null) {
                        blob.contains(kw)
                    } else {
                        // 拼音索引尚未就绪时退回原文匹配
                        user.name.contains(kw, ignoreCase = true) ||
                                user.sign.contains(kw, ignoreCase = true)
                    }
                }
            }
        }
    }

    LaunchedEffect(followViewModel.updating) {
        if (!followViewModel.updating && keyword.isEmpty()) {
            defaultFocusRequester.requestFocus(scope)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Box(
                modifier = Modifier.padding(
                    start = 48.dp,
                    top = 24.dp,
                    bottom = 8.dp,
                    end = 48.dp
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.user_homepage_follow),
                        fontSize = titleFontSize.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .width(360.dp)
                                .onFocusEvent {
                                    if (it.isFocused) keyboardController?.show()
                                    else keyboardController?.hide()
                                },
                            value = keyword,
                            onValueChange = { keyword = it },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.follow_search_hint),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            trailingIcon = if (keyword.isNotEmpty()) {
                                {
                                    IconButton(onClick = { keyword = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.search_input_soft_keybord_clear)
                                        )
                                    }
                                }
                            } else null,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.inverseSurface,
                                cursorColor = MaterialTheme.colorScheme.inverseSurface
                            )
                        )
                        Text(
                            text = stringResource(
                                R.string.load_data_count,
                                filteredUsers.size
                            ),
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            modifier = Modifier.padding(innerPadding),
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (!followViewModel.updating) {
                if (filteredUsers.isEmpty()) {
                    item(
                        span = { GridItemSpan(3) }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = stringResource(R.string.follow_search_empty))
                        }
                    }
                } else {
                    itemsIndexed(items = filteredUsers) { index, up ->
                        val upCardModifier =
                            if (index == 0) Modifier.focusRequester(defaultFocusRequester) else Modifier
                        UpCard(
                            modifier = upCardModifier,
                            face = up.avatar,
                            sign = up.sign,
                            username = up.name,
                            onFocusChange = {
                                if (it) currentIndex = index
                            },
                            onClick = {
                                UpInfoActivity.actionStart(
                                    context = context,
                                    mid = up.mid,
                                    name = up.name
                                )
                            }
                        )
                    }
                }
            } else {
                item(
                    span = { GridItemSpan(3) }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingTip()
                    }
                }
            }
        }
    }
}

@Composable
fun UpCard(
    modifier: Modifier = Modifier,
    face: String,
    sign: String,
    username: String,
    onFocusChange: (hasFocus: Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Surface(
        modifier = modifier
            .onFocusChanged { onFocusChange(it.hasFocus) }
            .size(280.dp, 80.dp),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            pressedContainerColor = MaterialTheme.colorScheme.surface
        ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.large),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(width = 3.dp, color = Color.White),
                shape = MaterialTheme.shapes.large
            )
        ),
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .padding(start = 12.dp, end = 8.dp)
                    .size(48.dp)
                    .clip(CircleShape),
                color = Color.White
            ) {
                AsyncImage(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    model = face,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds
                )
            }
            Column {
                Text(
                    text = username,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Preview
@Composable
fun UpCardPreview() {
    BVTheme {
        UpCard(
            face = "",
            sign = "一只业余做翻译的Klei迷，动态区UP（自称），缺氧官中反馈可私信",
            username = "username",
            onFocusChange = {},
            onClick = {}
        )
    }
}
