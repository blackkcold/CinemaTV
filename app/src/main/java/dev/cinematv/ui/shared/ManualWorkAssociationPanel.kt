package dev.cinematv.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.cinematv.CinemaViewModel
import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.usableMetadata
import dev.cinematv.data.library.associationConflicts
import dev.cinematv.data.library.associationEvidence
import dev.cinematv.data.library.visibleAssociationItems
import kotlinx.coroutines.delay
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.mobile.MobileGlassOutlinedButton
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.focus.rememberFocusTarget
import dev.cinematv.ui.typography.CinemaText as Text

/** Explicit local-only editor. Evidence is captured at opening and revalidated transactionally. */
@Composable
fun ManualWorkAssociationPanel(model: CinemaViewModel, tv: Boolean) {
    val libraryVersion = model.libraryVersion
    val selected = model.selected?.usableMetadata() ?: return
    val sourceId = MediaDomainMapper.entryId(selected)
    val source = model.associationCandidates.firstOrNull { MediaDomainMapper.entryId(it) == sourceId } ?: return
    var editing by remember(sourceId) { mutableStateOf(false) }
    var opened by remember(sourceId) { mutableStateOf(false) }
    val opener = remember(sourceId) { FocusRequester() }
    val overlay = model.workAssociations
    LaunchedEffect(editing) {
        if (tv && !editing && opened) { withFrameNanos { }; runCatching { opener.requestFocus() } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sourceId in overlay.entries) Text("手动关联 · 本机用户确认，非目录验证", color = TvColors.Secondary, fontSize = 13.sp)
        AssociationButton(model, tv, "open", "管理本地作品关联", Modifier.focusRequester(opener)) { opened = true; editing = true }
    }
    if (!editing) return
    // A library/catalog update does not silently alter the evidence the user is confirming.
    val initialSource = remember(sourceId, editing) { source }
    val initialOverlay = remember(sourceId, editing) { overlay }
    val candidates = remember(sourceId, editing) { model.associationCandidates.filter { MediaDomainMapper.entryId(it) != sourceId } }
    var target by remember(sourceId) { mutableStateOf<MediaItem?>(null) }
    var season by remember(sourceId) { mutableStateOf<Int?>(null) }
    var renderTime by remember(sourceId) { mutableLongStateOf(System.currentTimeMillis()) }
    // Expire an open dialog at the evidence boundary, even with no library update or user input.
    LaunchedEffect(initialSource, candidates) {
        while (true) {
            renderTime = System.currentTimeMillis()
            val expiry = (candidates + initialSource).map { it.catalogLicenseExpiresAt }
                .filter { it > renderTime }.minOrNull() ?: break
            delay((expiry - renderTime + 1).coerceAtLeast(1))
        }
    }
    val safeSource = visibleAssociationItems(listOf(initialSource), renderTime).firstOrNull()
    val visibleCandidates = visibleAssociationItems(candidates, renderTime)
    val visibleTarget = target?.let { visibleAssociationItems(listOf(it), renderTime).firstOrNull() }
    val close = remember(sourceId) { FocusRequester() }
    Dialog(onDismissRequest = { editing = false }) {
        LaunchedEffect(Unit) { if (tv) { withFrameNanos { }; runCatching { close.requestFocus() } } }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).background(TvColors.Background).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("本地作品关联", color = TvColors.Text, fontSize = 22.sp)
                AssociationButton(model, tv, "close", "取消 / 返回", Modifier.focusRequester(close)) { editing = false }
                Text(safeSource?.let(::associationEvidence) ?: "资料已过期，请关闭后重新查看", color = TvColors.Text, fontSize = 14.sp)
                Text("只改变本机导航。不会修改外部 ID、片源、剧集身份、收藏、状态或进度。请选择已保存在本机的目标片源，不会根据标题自动匹配。", color = TvColors.Secondary, fontSize = 13.sp)
            }
            if (!initialOverlay.writable) item { Text("关联数据版本不受支持或已损坏。原数据已保留，编辑已禁用。", color = TvColors.Text) }
            else {
                if (sourceId in initialOverlay.entries) item {
                    AssociationButton(model, tv, "unlink", "解除当前手动关联") {
                        model.unlinkWork(sourceId, initialOverlay.revision); editing = false
                    }
                }
                if (initialOverlay.undoEntries != null) item {
                    AssociationButton(model, tv, "undo", "撤销上次关联修改（本机全部关联）") {
                        model.undoWorkAssociation(initialOverlay.revision); editing = false
                    }
                }
                if (visibleCandidates.isEmpty()) item { Text("没有其他本地条目。先打开并保存另一片源，再回来关联。", color = TvColors.Secondary) }
                items(visibleCandidates, key = { MediaDomainMapper.entryId(it) }) { candidate ->
                    AssociationButton(model, tv, "target:${MediaDomainMapper.entryId(candidate)}", candidate.title + " · " + (candidate.source?.name ?: "目录")) { target = candidates.first { MediaDomainMapper.entryId(it) == MediaDomainMapper.entryId(candidate) } }
                }
                visibleTarget?.let { chosen ->
                    val affectedIds = initialOverlay.targetEntryIds(sourceId, MediaDomainMapper.entryId(chosen))
                    val affected = visibleCandidates.filter { MediaDomainMapper.entryId(it) in affectedIds }
                    val reviewedMembers = candidates.filter { MediaDomainMapper.entryId(it) in affectedIds }
                    val canConfirm = safeSource != null && affected.size == affectedIds.size &&
                        associationEvidence(safeSource) == associationEvidence(initialSource) &&
                        affected.all { member -> reviewedMembers.any { associationEvidence(it) == associationEvidence(member) } }
                    item {
                        Text("目标证据：${associationEvidence(chosen)}", color = TvColors.Text, fontSize = 14.sp)
                        affected.filter { MediaDomainMapper.entryId(it) != MediaDomainMapper.entryId(chosen) }.forEach { member ->
                            Text("其他成员证据：${associationEvidence(member)}",
                                color = TvColors.Secondary, fontSize = 13.sp)
                        }
                        val conflicts = affected.flatMap { member -> safeSource?.let { associationConflicts(it, member) }.orEmpty() }.distinct()
                        Text(if (conflicts.isEmpty()) "无已知字段冲突；仍需自行确认是同一作品。"
                            else "警告：${conflicts.joinToString("、")}。此操作仅建立手动关联，不会覆盖原始证据。", color = TvColors.Accent, fontSize = 14.sp)
                        Text("确认后包含：${(listOfNotNull(safeSource) + affected).joinToString { it.title + " [" + (it.source?.name ?: "目录") + " / " + it.id + "] 第" + (if (MediaDomainMapper.entryId(it) == sourceId) season else initialOverlay.seasonLabel(it))?.toString().orEmpty().ifBlank { "未知" } + "季" }}。仅移动当前片源，其他片源不会随其迁移。", color = TvColors.Secondary, fontSize = 13.sp)
                        Text("当前片源季数（手动标签）：${season?.let { if (it == 0) "特别篇 / 第0季" else "第${it}季" } ?: "未知"}", color = TvColors.Text)
                    }
                    item { AssociationButton(model, tv, "season-unknown", "季数未知") { season = null } }
                    item { AssociationButton(model, tv, "season-0", "特别篇 / 第0季") { season = 0 } }
                    item { AssociationButton(model, tv, "season-plus", "季数 +1") { season = ((season ?: 0) + 1).coerceAtMost(9999) } }
                    item { AssociationButton(model, tv, "season-minus", "季数 -1") { season = ((season ?: 1) - 1).coerceAtLeast(0) } }
                    if (!canConfirm) item { Text("资料已变化或过期，请关闭后重新查看完整证据。", color = TvColors.Accent) }
                    else item { AssociationButton(model, tv, "confirm", "我已核对证据，确认关联") {
                        model.associateWork(initialSource, requireNotNull(target), season, initialOverlay.revision, reviewedMembers); editing = false
                    } }
                }
            }
        }
    }
}

@Composable
private fun AssociationButton(model: CinemaViewModel, tv: Boolean, id: String, label: String,
    modifier: Modifier = Modifier, action: () -> Unit) {
    val target = rememberFocusTarget(model, "association", id)
    val tagged = modifier.testTag("association-$id").focusRequester(target)
    if (tv) GlassAction(label, tagged.fillMaxWidth().heightIn(min = 48.dp),
        onFocus = { model.focusCoordinator.remember(model.focusRoute, "association", id) }, onClick = action)
    else MobileGlassOutlinedButton(onClick = action, modifier = tagged.heightIn(min = 48.dp)) { Text(label) }
}
