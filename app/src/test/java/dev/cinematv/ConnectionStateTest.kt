package dev.cinematv

import dev.cinematv.data.LibreTvHttpException
import dev.cinematv.data.LibreTvProtocolException
import dev.cinematv.data.LiveSource
import dev.cinematv.data.ServerStatus
import dev.cinematv.data.TvSource
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection state is the only thing surfaces read for copy and the next action. These
 * cases pin the mapping so TV, phone and the hero cannot drift apart again.
 */
class ConnectionStateTest {

    private fun status(
        passwordRequired: Boolean = false,
        verified: Boolean = false,
        sources: List<TvSource> = emptyList(),
        live: List<LiveSource> = emptyList()
    ) = ServerStatus(passwordRequired, verified, sources, live, "2.21.0")

    private fun source(key: String) = TvSource(key, key, "https://$key.example/api.php/provide/vod")

    @Test fun noStoredServerIsUnconfigured() {
        assertEquals(ConnectionState.Unconfigured, connectionStateFor("", status()))
        assertEquals(ConnectionState.Unconfigured, connectionStateForFailure("", IOException("boom")))
    }

    @Test fun verifiedServerReportsItsSourceCounts() {
        val state = connectionStateFor("http://lan:8899",
            status(verified = true, sources = listOf(source("a"), source("b")), live = listOf(
                LiveSource("l", "live", "http://lan/live.m3u")
            )))
        assertEquals(ConnectionState.Ready(vodSources = 2, liveSources = 1), state)
        assertTrue(state.isUsable)
    }

    @Test fun verifiedWithoutSourcesIsItsOwnState() {
        val state = connectionStateFor("http://lan:8899", status(verified = true))
        assertEquals(ConnectionState.ReadyNoSources(verified = true), state)
        assertTrue(state.isUsable)
    }

    @Test fun passwordServerAsksForLoginInsteadOfClaimingDisconnection() {
        val state = connectionStateFor("http://lan:8899", status(passwordRequired = true))
        assertEquals(ConnectionState.AuthRequired(expired = false), state)
        assertFalse(state.isUsable)
    }

    @Test fun reachableServerWithoutPasswordIsAConfigurationProblem() {
        assertEquals(
            ConnectionState.PasswordNotConfigured,
            connectionStateFor("http://lan:8899", status(passwordRequired = false, verified = false))
        )
    }

    @Test fun lostSessionKeepsTheQueryAndAsksForLogin() {
        val state = connectionStateForFailure("http://lan:8899", LibreTvHttpException(401, "unauthorized"))
        assertEquals(ConnectionState.AuthRequired(expired = true), state)
        assertFalse(state.isUsable)
    }

    @Test fun protocolMismatchIsNotReportedAsANetworkProblem() {
        val state = connectionStateForFailure("http://lan:8899", LibreTvProtocolException("unexpected body"))
        assertTrue(state is ConnectionState.Incompatible)
        assertEquals("服务器响应格式无效，请检查 LibreTV 版本与连接配置", ConnectionCopy.summary(state))
    }

    @Test fun networkFailureIsRetryableAndKeepsTheServerMessage() {
        val state = connectionStateForFailure("http://lan:8899", IOException("timeout"))
        assertTrue(state is ConnectionState.Unreachable)
        state as ConnectionState.Unreachable
        assertEquals(ConnectionFailure.TIMEOUT, state.failure)
        assertTrue(state.message.isNotBlank())
    }

    @Test fun everyStateHasOneSentenceAndOneAction() {
        val states = listOf(
            ConnectionState.Unconfigured,
            ConnectionState.Connecting,
            ConnectionState.Authenticating,
            ConnectionState.AuthRequired(expired = false),
            ConnectionState.AuthRequired(expired = true),
            ConnectionState.AuthRejected,
            ConnectionState.PasswordNotConfigured,
            ConnectionState.Unreachable(ConnectionFailure.NETWORK, "网络不可用"),
            ConnectionState.Incompatible("版本不兼容"),
            ConnectionState.Ready(1, 2),
            ConnectionState.ReadyNoSources(verified = true)
        )
        states.forEach { state ->
            assertTrue("missing copy for $state", ConnectionCopy.summary(state).isNotBlank())
            val action = ConnectionCopy.action(state)
            assertEquals(
                "usable states must offer content, not a connection edit",
                state.isUsable,
                action == ConnectionAction.SEARCH || action == ConnectionAction.EDIT_SOURCES
            )
        }
    }

    @Test fun waitingStatesDoNotOfferAnActionThatCouldRaceTheAttempt() {
        assertEquals(ConnectionAction.WAIT, ConnectionCopy.action(ConnectionState.Connecting))
        assertEquals(ConnectionAction.WAIT, ConnectionCopy.action(ConnectionState.Authenticating))
    }
}
