package dev.cinematv

import dev.cinematv.data.LibreTvHttpException
import dev.cinematv.data.LibreTvProtocolException
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

    @Test fun typedStatusCannotBeReclassifiedByAnUntrustedServerMessage() {
        assertEquals(ConnectionFailure.RATE_LIMITED,
            ConnectionErrors.classify(LibreTvHttpException(429, "unauthorized HTTP 401")))
        assertEquals(ConnectionFailure.SERVER,
            ConnectionErrors.classify(LibreTvHttpException(503, "HTTP 401")))
        assertEquals(ConnectionFailure.AUTHENTICATION,
            ConnectionErrors.classify(LibreTvHttpException(401, "未登录")))
        assertEquals(ConnectionFailure.PROTOCOL,
            ConnectionErrors.classify(LibreTvProtocolException("missing episodes")))
    }
}
