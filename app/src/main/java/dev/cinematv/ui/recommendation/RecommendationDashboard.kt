package dev.cinematv.ui.recommendation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cinematv.data.MediaItem
import dev.cinematv.data.recommendation.LocalRecommendationSnapshot
import dev.cinematv.data.recommendation.LocalRecommendationWeights
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Reads the exact snapshot used to populate a shelf. Never runs a second, approximate scorer. */
@Composable
fun RecommendationDashboardDialog(
    snapshots: List<Pair<String, LocalRecommendationSnapshot>>,
    tv: Boolean,
    onDismiss: () -> Unit,
    onOpenItem: (MediaItem) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val current = snapshots.getOrNull(tab) ?: snapshots.firstOrNull()
    val close = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CinemaGlassSurface(Modifier.fillMaxWidth(if (tv) .82f else .96f).fillMaxHeight(.9f), radius = 24.dp) {
            LazyColumn(Modifier.testTag("recommendation-dashboard").fillMaxSize().padding(if (tv) 24.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本地推荐看板", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        DashboardAction("关闭", tv, Modifier.testTag("recommendation-dashboard-close").focusRequester(close), onDismiss)
                    }
                }
                item { Text("偏好与排序只在本机计算。分数不是喜欢概率，也不是作品质量评分。", color = Color.LightGray, fontSize = 13.sp) }
                if (snapshots.size > 1) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        snapshots.forEachIndexed { index, pair ->
                            DashboardAction((if (index == tab) "✓ " else "") + pair.first, tv, onClick = { tab = index })
                        }
                    }
                }
                if (current == null) item {
                    Text("暂无排序快照。连接目录或在片库添加作品后可查看；这里不会生成模拟画像。", color = Color.White)
                } else {
                    val snapshot = current.second
                    val ranking = snapshot.ranking
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${current.first} · ${if (ranking.coldStart) "冷启动 / 目录原顺序" else "本地内容推荐"}", color = Color.White, fontSize = 19.sp)
                            Text("${ranking.policy.version}\nKotlin 基线实现 · Rocchio 加权标签向量 + 余弦 + MMR\n未使用语义嵌入或预训练推荐模型。", color = Color.LightGray, fontSize = 13.sp)
                            Text("快照：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(snapshot.asOfMs))}\n有效偏好作品 ${ranking.positiveWorkCount} · 候选 ${ranking.eligibleCandidateCount} · 输出 ${snapshot.rows.size}\n排除负反馈 ${ranking.excludedCandidateCount} · 过期元数据 ${snapshot.expiredCandidateCount}", color = Color.LightGray, fontSize = 13.sp)
                            if (ranking.truncatedCandidateCount > 0 || ranking.truncatedPositiveWorkCount > 0)
                                Text("本次容量限制：省略候选 ${ranking.truncatedCandidateCount}，偏好作品 ${ranking.truncatedPositiveWorkCount}。", color = Color(0xFFFFC678), fontSize = 13.sp)
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("真实权重与规则", color = Color.White, fontSize = 18.sp)
                            Text("收藏 ${LocalRecommendationWeights.FAVORITE} · 在看 ${LocalRecommendationWeights.WATCHING} · 想看 ${LocalRecommendationWeights.WANT_TO_WATCH}\n看过 ${LocalRecommendationWeights.WATCHED} · 播放进度 ${LocalRecommendationWeights.MEASURED_PROGRESS}\n类型 ${LocalRecommendationWeights.GENRE} · 演员 ${LocalRecommendationWeights.ACTOR}\n相关性 ${number(ranking.policy.relevanceWeight)} · 多样性 ${number(1 - ranking.policy.relevanceWeight)}", color = Color.LightGray, fontSize = 13.sp)
                            Text("同一作品取最强证据，不因重复点击累加。先未看、再在看、最后看过；层级之间不直接比较分数。不感兴趣/弃剧始终排除。无有效类型/演员偏好时保留目录顺序。", color = Color.LightGray, fontSize = 13.sp)
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("偏好向量 / 优先标签", color = Color.White, fontSize = 18.sp)
                            Text("这是归一化后的稀疏标签向量，不是语义空间或因果图。", color = Color.LightGray, fontSize = 13.sp)
                            val features = ranking.profile.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                            if (features.isEmpty()) Text("还没有足够的类型/演员证据。收藏或标记有元数据的作品后自动更新。", color = Color.LightGray, fontSize = 13.sp)
                            else {
                                features.take(16).forEach { (tag, weight) -> Text("${tagLabel(tag)}  ${number(weight)}", color = Color.White, fontSize = 14.sp) }
                                if (features.size > 16) Text("显示前 16 / ${features.size} 个标签，其余仍参与计算。", color = Color.LightGray, fontSize = 12.sp)
                            }
                        }
                    }
                    item { Text("本次排序解释 · 前 ${minOf(20, snapshot.rows.size)} / ${snapshot.rows.size}", color = Color.White, fontSize = 18.sp) }
                    itemsIndexed(snapshot.rows.take(20), key = { index, _ -> "${snapshot.asOfMs}:$tab:$index" }) { _, row ->
                        val trace = row.trace
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            DashboardAction("${trace.finalRank}. ${row.item.title}", tv,
                                Modifier.testTag("recommendation-row-${trace.finalRank}")) { onOpenItem(row.item) }
                            Text(row.reason, color = Color.LightGray, fontSize = 13.sp)
                            Text("余弦 ${number(trace.similarity)} · 相关性贡献 ${number(trace.relevanceContribution)} − 多样性 ${number(trace.diversityPenalty)} = 本轮选择分 ${number(trace.selectionScore)}\n重排前 ${trace.rankBeforeDiversity} → 最终 ${trace.finalRank} · 观看层级 ${trace.seenTier}", color = Color.LightGray, fontSize = 12.sp)
                            if (trace.contributions.isNotEmpty()) Text(trace.contributions.take(6).joinToString("\n") {
                                "${tagLabel(it.tag)}：${number(it.profileWeight)} × ${number(it.candidateWeight)} = ${number(it.contribution)}"
                            } + if (trace.contributions.size > 6) "\n仅展示贡献最高的 6 项，全部项均计入余弦。" else "", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                    item { Text("本页只读，不上传画像或导出播放地址。标记与观看记录继续使用原有本机片库；清除观看历史不会删除收藏与片库状态。", color = Color.LightGray, fontSize = 12.sp) }
                }
            }
        }
        if (tv) LaunchedEffect(Unit) { withFrameNanos { }; close.requestFocus() }
    }
}

@Composable
private fun DashboardAction(label: String, tv: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (tv) GlassAction(label, modifier.heightIn(min = 38.dp), onClick = onClick)
    else TextButton(onClick = onClick, modifier = modifier) { Text(label, color = Color.White) }
}

private fun number(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
private fun tagLabel(tag: String): String = when {
    tag.startsWith("genre:") -> "类型 · ${tag.substringAfter(':')}"
    tag.startsWith("actor:") -> "演员 · ${tag.substringAfter(':')}"
    else -> tag
}
