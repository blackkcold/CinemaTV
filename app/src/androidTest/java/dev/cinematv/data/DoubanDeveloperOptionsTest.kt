package dev.cinematv.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.data.douban.DoubanDeveloperOptions
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All settings and cleanup are synthetic; never opens a WebView or the production vault. */
@RunWith(AndroidJUnit4::class)
class DoubanDeveloperOptionsTest {
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "opt-test-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val file = File(context.noBackupFilesDir, "$namespace.v1")
        var sessionClears = 0
        var webClears = 0
        var failCleanup = false
        var failWrite = false
        var beforeSessionCleanup: suspend () -> Unit = {}
        fun controller(channel: String = "dev") = DoubanDeveloperOptions(
            context, namespace, channel,
            clearSession = { sessionClears++; beforeSessionCleanup(); if (failCleanup) throw IOException("Synthetic cleanup failure") },
            clearWeb = { webClears++ }, legacyExists = { false },
            beforeWrite = { if (failWrite) throw IOException("Synthetic settings write failure") }
        )
        fun record() = JSONObject(file.readText())
        fun close() { listOf("", ".bak", ".new").forEach { File(file.path + it).delete() } }
    }

    @Test fun freshSettingsDefaultOffWithoutCleanupOrFileCreation() = runBlocking {
        val f = Fixture()
        try {
            val options = f.controller()
            assertFalse(options.access.isEnabled)
            options.initialize()
            assertFalse(options.access.isEnabled)
            assertFalse(options.busy.value)
            assertEquals(0, f.sessionClears)
            assertEquals(0, f.webClears)
            assertFalse(f.file.exists())
        } finally { f.close() }
    }

    @Test fun explicitEnablePersistsAcrossNewControllerInstance() = runBlocking {
        val f = Fixture()
        try {
            val options = f.controller()
            options.initialize()
            assertTrue(options.enable())
            assertTrue(options.access.isEnabled)
            assertTrue(f.record().getBoolean("enabled"))
            val restarted = f.controller()
            assertFalse(restarted.access.isEnabled)
            restarted.initialize()
            assertTrue(restarted.access.isEnabled)
            assertEquals(0, f.sessionClears)
            assertEquals(0, f.webClears)
        } finally { f.close() }
    }

    @Test fun disablePersistsOffAndClearsSyntheticSessionAndPendingWebLogin() = runBlocking {
        val f = Fixture()
        try {
            val options = f.controller()
            options.initialize()
            assertTrue(options.enable())
            options.beginWebLogin()
            assertTrue(f.record().getBoolean("webPending"))
            assertTrue(options.disableAndClear())
            assertFalse(options.access.isEnabled)
            assertFalse(f.record().getBoolean("enabled"))
            assertFalse(f.record().getBoolean("cleanupPending"))
            assertFalse(f.record().getBoolean("webPending"))
            assertEquals(1, f.sessionClears)
            assertEquals(1, f.webClears)
            val restarted = f.controller()
            restarted.initialize()
            assertFalse(restarted.access.isEnabled)
            assertEquals(1, f.sessionClears)
        } finally { f.close() }
    }

    @Test fun corruptSettingsWithCleanupFailureStayOffAndRetryAfterRestart() = runBlocking {
        val f = Fixture()
        try {
            f.file.writeText("not valid settings")
            f.failCleanup = true
            val options = f.controller()
            options.initialize()
            assertFalse(options.access.isEnabled)
            assertFalse(options.enable())
            assertFalse(f.record().getBoolean("enabled"))
            assertTrue(f.record().getBoolean("cleanupPending"))
            assertTrue(f.record().getBoolean("webPending"))
            assertTrue(options.notice.value.isNotBlank())
            f.failCleanup = false
            val restarted = f.controller()
            restarted.initialize()
            assertFalse(restarted.access.isEnabled)
            assertFalse(f.record().getBoolean("cleanupPending"))
            assertFalse(f.record().getBoolean("webPending"))
            assertEquals(2, f.sessionClears)
            assertEquals(1, f.webClears)
        } finally { f.close() }
    }

    @Test fun enableWriteFailureNeverGrantsAccess() = runBlocking {
        val f = Fixture()
        try {
            val options = f.controller()
            options.initialize()
            f.failWrite = true
            assertFalse(options.enable())
            assertFalse(options.access.isEnabled)
            assertFalse(f.file.exists())
            f.failWrite = false
            val restarted = f.controller()
            restarted.initialize()
            assertFalse(restarted.access.isEnabled)
            assertEquals(0, f.sessionClears)
            assertEquals(0, f.webClears)
        } finally { f.close() }
    }

    @Test fun cancelledInitializationCompletesCleanupAndResetsBusy() = runBlocking {
        val f = Fixture()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        f.file.writeText("corrupt synthetic settings requiring cleanup")
        f.beforeSessionCleanup = { cleanupEntered.complete(Unit); releaseCleanup.await() }
        val options = f.controller()
        val caller = launch(Dispatchers.Default) { options.initialize() }
        try {
            withTimeout(10_000) { cleanupEntered.await() }
            assertTrue(options.busy.value)
            caller.cancel()
            // Cleanup must still own initialization until the suspended I/O work finishes.
            assertTrue(options.busy.value)
            releaseCleanup.complete(Unit)
            withTimeout(10_000) { caller.join() }
            assertTrue(caller.isCancelled)
            assertFalse(options.busy.value)
            assertFalse(options.access.isEnabled)
            assertFalse(f.record().getBoolean("cleanupPending"))
            assertEquals(1, f.sessionClears)
            assertEquals(1, f.webClears)
            options.initialize()
            assertFalse(options.busy.value)
            assertTrue(options.enable())
            assertTrue(options.disableAndClear())
            assertFalse(options.busy.value)
            assertFalse(options.access.isEnabled)
            assertEquals(2, f.sessionClears)
        } finally {
            releaseCleanup.complete(Unit)
            caller.cancelAndJoin()
            f.close()
        }
    }

    @Test fun staleWebLeaseCannotClearNewLoginAndRestartCleansPendingMarker() = runBlocking {
        val f = Fixture()
        try {
            val options = f.controller()
            options.initialize()
            assertTrue(options.enable())
            val oldLease = options.beginWebLogin()
            val newLease = options.beginWebLogin()
            assertNotEquals(oldLease, newLease)
            assertFalse(options.isCurrentWebLogin(oldLease))
            assertTrue(options.isCurrentWebLogin(newLease))
            options.clearWebLogin(oldLease)
            assertEquals(0, f.webClears)
            assertTrue(f.record().getBoolean("webPending"))
            assertTrue(options.isCurrentWebLogin(newLease))
            val restarted = f.controller()
            restarted.initialize()
            assertEquals(1, f.webClears)
            assertFalse(f.record().getBoolean("webPending"))
            assertTrue(restarted.access.isEnabled)
        } finally { f.close() }
    }

    @Test fun currentWebLeaseCleanupInvalidatesLeaseAndClearsPendingMarker() = runBlocking {
        val f = Fixture()
        try {
            val options = f.controller()
            options.initialize()
            assertTrue(options.enable())
            val lease = options.beginWebLogin()
            assertTrue(options.isCurrentWebLogin(lease))
            options.clearWebLogin(lease)
            assertFalse(options.isCurrentWebLogin(lease))
            assertFalse(f.record().getBoolean("webPending"))
            assertEquals(1, f.webClears)
            options.clearWebLogin(lease)
            assertEquals(1, f.webClears)
        } finally { f.close() }
    }

    @Test fun nonDevControllerCannotEnableEvenWithPersistedOptIn() = runBlocking {
        val f = Fixture()
        try {
            val dev = f.controller()
            dev.initialize()
            assertTrue(dev.enable())
            val otherChannel = f.controller("stable")
            otherChannel.initialize()
            assertFalse(otherChannel.access.isEnabled)
            assertFalse(otherChannel.enable())
            assertFalse(f.record().getBoolean("enabled"))
        } finally { f.close() }
    }
}
