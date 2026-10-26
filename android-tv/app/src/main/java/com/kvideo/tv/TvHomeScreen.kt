package com.kvideo.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * 原生电视墙首页 (Compose for TV)。
 *
 * 结构参考 Google 官方 android/tv-samples -> JetStreamCompose (Apache-2.0):
 *   LazyColumn 竖向堆行 + 每行 LazyRow 横向海报 + 焦点联动背景。
 *
 * 焦点三件套 (tv-material 1.1.0 / focus 1.0):
 *   - focusGroup()      : 整行包成焦点组, 方向键在行内循环
 *   - focusRestorer{}   : 离开行再回来回到上次那张卡
 *   - focusProperties   : 行首卡左键 Cancel, 不外溢到背景
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)
@Composable
fun TvHomeScreen(
    baseUrl: String,
    onOpenWeb: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val repo = remember(baseUrl) { HomeRepository(baseUrl) }
    var rows by remember { mutableStateOf<List<HomeRepository.Row>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var backdrop by remember { mutableStateOf<DoubanSubject?>(null) }

    // 背景图随焦点变化
    LaunchedEffect(Unit) {
        try {
            val loaded = repo.loadRows()
            rows = loaded
            if (loaded.all { it.items.isEmpty() }) {
                errorMsg = "无法加载推荐内容"
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0B0D12))) {

        // ---- 背景大图 (焦点联动) ----
        backdrop?.let { subject ->
            AsyncImage(
                model = subject.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.28f)
            )
        }
        // 底部渐变, 保证文字可读
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.15f),
                        0.45f to Color.Black.copy(alpha = 0.62f),
                        1f to Color.Black.copy(alpha = 0.90f)
                    )
                )
        )

        // ---- 顶部标题栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 60.dp, end = 60.dp, top = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "紫气东来影院",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(28.dp))
            Text(
                "原生电视墙",
                color = Color(0xFF3B82F6),
                fontSize = 17.sp
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onOpenWeb(baseUrl) },
                modifier = Modifier.focusRestorer { androidx.compose.ui.focus.FocusRequester.Default }
            ) {
                Text("网页版 / 搜索", fontSize = 16.sp)
            }
        }

        // ---- 内容区 ----
        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在加载推荐…", color = Color(0xFF9EA7BA), fontSize = 20.sp)
                }
            }

            errorMsg != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            errorMsg ?: "",
                            color = Color(0xFF9EA7BA),
                            fontSize = 19.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "点上方「网页版 / 搜索」可继续使用",
                            color = Color(0xFF5C6478),
                            fontSize = 15.sp
                        )
                    }
                }
            }

            else -> {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 96.dp, bottom = 60.dp)
                ) {
                    itemsIndexed(rows) { rowIndex, row ->
                        if (row.items.isEmpty()) return@itemsIndexed
                        TvMovieRow(
                            title = row.spec.label,
                            items = row.items,
                            onFocusItem = { backdrop = it },
                            onClick = { subject ->
                                onOpenWeb(baseUrl + "/search?q=" + java.net.URLEncoder.encode(subject.title, "UTF-8"))
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一行海报 (电视墙的核心单元)。
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)
@Composable
private fun TvMovieRow(
    title: String,
    items: List<DoubanSubject>,
    onFocusItem: (DoubanSubject) -> Unit,
    onClick: (DoubanSubject) -> Unit
) {
    // 焦点记忆: 离开这行再回来, 回到上次那张卡
    val (rowFocus, firstItemFocus) = remember { FocusRequester.createRefs() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup()
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 60.dp, bottom = 12.dp)
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(rowFocus)
                .focusRestorer { firstItemFocus },
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = 60.dp)
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, subject ->
                TvMovieCard(
                    subject = subject,
                    modifier = Modifier
                        .focusRequester(firstItemFocus)
                        .focusProperties {
                            // 行首卡的左键不外溢 (防止焦点跳到背景)
                            left = if (index == 0) FocusRequester.Cancel else FocusRequester.Default
                        }
                        .onFocusChangedCompat { focused ->
                            if (focused) onFocusItem(subject)
                        },
                    onClick = { onClick(subject) }
                )
            }
        }
    }
}

/**
 * 海报卡片。
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)
@Composable
private fun TvMovieCard(
    subject: DoubanSubject,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "cardScale")

    Box(
        modifier = modifier
            .width(196.dp)
            .aspectRatio(10.5f / 16f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF151922))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color(0xFF3B82F6) else Color(0xFF262C38),
                shape = RoundedCornerShape(12.dp)
            )
            .focusClickable(onClick = onClick)
    ) {
        AsyncImage(
            model = subject.coverUrl,
            contentDescription = subject.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 评分角标
        if (subject.rate.isNotBlank() && subject.rate != "0") {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    subject.rate,
                    color = Color(0xFFF5B942),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 标题条 (获得焦点时才显示, 减少视觉噪音)
        if (focused) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.88f)
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Text(
                    subject.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 焦点变化回调。
 *
 * Compose 1.6.8 的 onFocusChanged 标注为 ExperimentalComposeUiApi, 必须显式 OptIn。
 * (1.7.0 起已转正, 但本项目锁在 1.6.8 以兼容 compileSdk 34 / AGP 8.2)
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.onFocusChangedCompat(onChange: (Boolean) -> Unit): Modifier =
    this.onFocusChanged { state -> onChange(state.isFocused) }

/**
 * 可聚焦 + 可点击 (TV 上必须两者兼有才能响应 OK 键)。
 */
private fun Modifier.focusClickable(onClick: () -> Unit): Modifier = this
    .then(
        Modifier.focusableCompat()
    )
    .then(
        Modifier.clickableNoRipple(onClick)
    )
