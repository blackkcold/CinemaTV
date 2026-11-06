package dev.cinematv.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LibreTvConnectionTest {
    @Test fun oldAuthenticationAndUnauthorizedResponsesCannotMutateTheNewSession() {
        val sessions = LibreTvConnectionSession("https://a.example.test")
        val old = sessions.context()
        sessions.receive(old, 200, "session=a; HttpOnly")
        val current = sessions.replace("https://b.example.test")
        assertNull(sessions.cookie(current))
        sessions.receive(current, 200, "session=b; HttpOnly")
        assertCancelled { sessions.receive(old, 200, "session=late-a") }
        assertCancelled { sessions.receive(old, 401, null) }
        assertEquals("session=b", sessions.cookie(current))
        assertCancelled { sessions.cookie(old) }
    }

    @Test fun returningToTheSameServerDoesNotReviveOldRequestOwnership() {
        val sessions = LibreTvConnectionSession("https://a.example.test")
        val old = sessions.context()
        sessions.replace("https://b.example.test")
        val current = sessions.replace("https://a.example.test")
        assertNotEquals(old.generation, current.generation)
        var applied = false
        assertCancelled { sessions.withCurrent(old) { applied = true } }
        assertFalse(applied)
        assertNull(sessions.cookie(current))
    }

    @Test fun refreshingTheSameServerRetainsOnlyTheCurrentCookieAndReplacesRequests() {
        val sessions = LibreTvConnectionSession("https://a.example.test")
        val old = sessions.context()
        sessions.receive(old, 200, "session=current; HttpOnly")
        val refreshed = sessions.replace(old.server)
        assertEquals("session=current", sessions.cookie(refreshed))
        assertCancelled { sessions.receive(old, 401, null) }
    }

    @Test fun requestsIssuedDuringAuthenticationCannotClearTheNewCookie() {
        val sessions = LibreTvConnectionSession("https://a.example.test")
        val connection = sessions.replace("https://a.example.test", clearSession = true)
        val oldRequest = sessions.snapshot(connection)
        val authRequest = sessions.snapshot(connection)
        val authenticated = sessions.receive(authRequest, 200, "session=new; HttpOnly")
        assertCancelled { sessions.receive(oldRequest, 401, null) }
        assertCancelled { sessions.withCurrent(oldRequest) { fail("Old unverified status applied") } }
        assertTrue(sessions.isCurrent(connection, authenticated.revision))
        assertFalse(sessions.isCurrent(connection, oldRequest.revision))
        assertEquals("session=new", sessions.cookie(connection))
    }

    private fun assertCancelled(block: () -> Unit) {
        try { block(); fail("Replaced connection must not produce effects") }
        catch (_: CancellationException) { }
    }
}
