package dev.cinematv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.data.*
import dev.cinematv.data.library.associationEvidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileManualWorkAssociationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun explicitEvidenceCancelBackSpecialsUnknownUnlinkUndo() = associationEditor(rule, rule.activity, false)
}

@RunWith(AndroidJUnit4::class)
class TvManualWorkAssociationTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    @Test fun explicitEvidenceCancelBackSpecialsUnknownUnlinkUndo() = associationEditor(rule, rule.activity, true)
}

/** Actual editor + VM + Room; all source metadata and streams are synthetic loopback fixtures. */
@OptIn(ExperimentalTestApi::class)
private fun associationEditor(rule: ComposeContentTestRule, activity: ComponentActivity, tv: Boolean) {
    val model = ViewModelProvider(activity)[CinemaViewModel::class.java]
    UiFixtureServer(activity).use { server ->
        fun item(key: String, year: String) = MediaItem("association-$key-${server.base}", "Association $key",
            source = TvSource(key, "Synthetic $key", "${server.base}/$key"), isTv = true, seasonNumber = 1,
            year = year, metadataIds = mapOf("tmdb" to "synthetic-$key"),
            episodes = listOf("${server.base}/stream.mp4?episode=0", "${server.base}/stream.mp4?episode=1"),
            episodeIds = listOf("$key-pilot", "$key-second"), playlistKnown = true,
            poster = server.poster, backdrop = server.backdrop(0))
        val source = item("source", "2025")
        val target = item("target", "2026")
        val sourceId = MediaDomainMapper.entryId(source)
        val targetId = MediaDomainMapper.entryId(target)
        listOf(source, target).forEach(server::registerDetail)
        runBlocking {
            model.repo.library.initialize()
            for ((index, entry) in listOf(source, target).withIndex()) {
                model.repo.library.cacheItem(entry)
                model.repo.library.saveProgress(entry, 12000L + index * 1000, 0, 90000)
                model.repo.library.saveProgress(entry, 23000L + index * 1000, 1, 90000)
                model.repo.setLibraryStatus(entry, if (index == 0) LibraryStatus.WATCHING else LibraryStatus.WATCHED)
            }
            if (!model.repo.isFavorite(source)) model.repo.toggleFavorite(source)
        }
        rule.runOnIdle { model.repo.configureServer(server.base); model.navigate(Page.LIBRARY); model.open(source) }
        rule.waitUntil(15000) { !model.busy && model.selected?.id == source.id }
        val selected = model.selected!!
        val baseline = model.workAssociations
        val recordIds = setOf(sourceId, targetId)
        fun records() = model.repo.library.snapshot.records.filter { MediaDomainMapper.entryId(it.item) in recordIds }
        val originalRecords = records()
        val originalCheckpoints = model.repo.library.snapshot.episodeProgress.filterValues { it.sourceMappingId in recordIds }
        fun assertUnchangedPlaybackAndFlags() = rule.runOnIdle {
            assertEquals(originalRecords, records())
            assertEquals(originalCheckpoints, model.repo.library.snapshot.episodeProgress.filterValues { it.sourceMappingId in recordIds })
            assertEquals(source.metadataIds, model.selected!!.metadataIds)
            assertEquals(1, model.selected!!.seasonNumber)
            assertEquals(sourceId, MediaDomainMapper.entryId(model.selected!!))
            assertTrue(model.repo.isFavorite(source)); assertFalse(model.repo.isFavorite(target))
        }
        fun click(node: SemanticsNodeInteraction) {
            if (tv) {
                node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                node.assertIsFocused()
                node.performKeyInput { pressKey(Key.DirectionCenter) }
            } else node.performClick()
            rule.waitForIdle()
        }
        fun dialogNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
            rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog())).performScrollToNode(matcher)
            return rule.onNode(matcher)
        }
        fun choose(tag: String) = click(dialogNode(hasTestTag("association-$tag")))
        fun open() {
            rule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasTestTag("association-open"))
            click(rule.onNodeWithTag("association-open"))
            if (tv) rule.onNodeWithTag("association-close").assertIsFocused()
            dialogNode(hasText(associationEvidence(selected))).assertExists()
        }
        fun selectTarget() {
            choose("target:$targetId")
            dialogNode(hasText("目标证据：${associationEvidence(target)}")).assertExists()
            dialogNode(hasText("tmdb 外部 ID 冲突", substring = true)).assertExists()
            dialogNode(hasText("年份不同", substring = true)).assertExists()
        }
        fun closedWithFocus() {
            rule.onNode(isDialog()).assertDoesNotExist()
            if (tv) rule.onNodeWithTag("association-open").assertIsFocused()
            assertUnchangedPlaybackAndFlags()
        }
        open(); selectTarget(); choose("season-0"); choose("close")
        closedWithFocus()
        rule.runOnIdle { assertEquals(baseline, model.workAssociations) }

        open(); selectTarget(); choose("season-unknown")
        Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.pressBack())
        rule.waitForIdle()
        closedWithFocus()
        rule.runOnIdle { assertEquals(baseline, model.workAssociations) }

        open(); selectTarget(); choose("season-0")
        dialogNode(hasText("当前片源季数（手动标签）：特别篇 / 第0季")).assertExists()
        choose("confirm")
        rule.waitUntil(15000) { model.workAssociations.entries[sourceId]?.season == 0 }
        closedWithFocus()
        rule.runOnIdle { assertEquals(model.workAssociations.workId(source), model.workAssociations.workId(target)) }

        // Reopen the actual editor and explicitly override to unknown, never borrow provider season 1.
        open(); selectTarget(); choose("season-unknown")
        dialogNode(hasText("当前片源季数（手动标签）：未知")).assertExists()
        val specialsRevision = model.workAssociations.revision
        choose("confirm")
        rule.waitUntil(15000) { model.workAssociations.revision != specialsRevision }
        closedWithFocus()
        val unknownEntries = model.workAssociations.entries
        rule.runOnIdle { assertTrue(sourceId in unknownEntries); assertNull(model.workAssociations.seasonLabel(source)) }
        open(); choose("unlink")
        rule.waitUntil(15000) { sourceId !in model.workAssociations.entries }
        closedWithFocus()
        rule.runOnIdle {
            assertFalse(targetId in model.workAssociations.entries)
            assertEquals(MediaDomainMapper.workId(source), model.workAssociations.workId(source))
        }
        open(); choose("undo")
        rule.waitUntil(15000) { model.workAssociations.entries == unknownEntries }
        closedWithFocus()
        rule.runOnIdle { assertNull(model.workAssociations.seasonLabel(source)); assertNull(model.workAssociations.undoEntries) }
    }
}
