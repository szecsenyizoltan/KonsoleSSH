package hu.billman.konsolessh.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

/** The Drive client against a local stand-in for the parts of the Drive API it uses. */
class DriveAppDataStoreTest {

    private var file: Pair<String, String>? = null
    private val requests = mutableListOf<String>()
    private var acceptedToken = "good"
    private var quotaExceeded = false

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { handle(it) }
        start()
    }

    private fun store(token: String = "good") =
        DriveAppDataStore(accessToken = { token }, baseUrl = "http://127.0.0.1:${server.address.port}")

    @After
    fun tearDown() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val method = ex.requestHeaders.getFirst("X-HTTP-Method-Override") ?: ex.requestMethod
        val path = ex.requestURI.path
        val query = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
        requests += "$method $path"
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val (code, answer) = when {
            ex.requestHeaders.getFirst("Authorization") != "Bearer $acceptedToken" -> 401 to "{}"
            quotaExceeded && method == "POST" ->
                403 to """{"error":{"code":403,"errors":[{"reason":"storageQuotaExceeded"}]}}"""
            method == "GET" && path == "/drive/v3/files" -> {
                assertTrue(query.contains("spaces=appDataFolder"))
                200 to (file?.let { """{"files":[{"id":"${it.first}"}]}""" } ?: """{"files":[]}""")
            }
            method == "GET" && path == "/drive/v3/files/${file?.first}" -> 200 to file!!.second
            method == "POST" && path == "/upload/drive/v3/files" -> {
                assertTrue(body.contains(""""parents":["appDataFolder"]"""))
                file = "f1" to body.substringAfter("Content-Type: application/json\r\n\r\n").substringBefore("\r\n--")
                200 to """{"id":"f1"}"""
            }
            method == "PATCH" && path == "/upload/drive/v3/files/${file?.first}" -> {
                file = file!!.first to body
                200 to """{"id":"${file!!.first}"}"""
            }
            else -> 404 to "{}"
        }
        val bytes = answer.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `no file yet reads as null`() = runBlocking {
        assertNull(store().read())
    }

    @Test
    fun `the first write creates the file in the app data folder, the next replaces it`() = runBlocking {
        store().write("""{"v":1}""")
        assertEquals("""{"v":1}""", store().read())
        store().write("""{"v":2}""")
        assertEquals("""{"v":2}""", store().read())
        assertEquals(1, requests.count { it == "POST /upload/drive/v3/files" })
        assertEquals(1, requests.count { it == "PATCH /upload/drive/v3/files/f1" })
    }

    @Test
    fun `a refused token is forgotten and the request retried with a fresh one`() = runBlocking {
        val tokens = ArrayDeque(listOf("expired", "good"))
        val forgotten = mutableListOf<String>()
        val store = DriveAppDataStore(
            accessToken = { tokens.removeFirst() },
            forgetToken = { forgotten += it },
            baseUrl = "http://127.0.0.1:${server.address.port}",
        )
        assertNull(store.read())
        assertEquals(listOf("expired"), forgotten)
    }

    @Test(expected = DriveFullException::class)
    fun `a full Google storage is reported as such`() {
        quotaExceeded = true
        runBlocking { store().write("{}") }
    }

    @Test(expected = DriveAuthException::class)
    fun `a refused token is reported as an authorization problem`() {
        runBlocking { store(token = "expired").read() }
    }
}
