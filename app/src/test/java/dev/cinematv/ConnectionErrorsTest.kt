package dev.cinematv

import org.junit.Assert.*
import org.junit.Test

class ConnectionErrorsTest {
    @Test fun categorizesAuthAndNetwork() {
        assertEquals(ConnectionFailure.AUTHENTICATION,
            ConnectionErrors.classify(Exception("HTTP 401: missing session")))
        assertEquals(ConnectionFailure.FORBIDDEN,
            ConnectionErrors.classify(Exception("HTTP 403")))
        assertEquals(ConnectionFailure.NETWORK,
            ConnectionErrors.classify(Exception("Connection refused")))
        assertEquals(ConnectionFailure.TIMEOUT,
            ConnectionErrors.classify(Exception("Socket timed out")))
    }
}
