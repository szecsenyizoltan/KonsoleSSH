package hu.billman.konsolessh.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Where the encrypted sync file lives; [DriveAppDataStore] in the app, a fake in tests. */
interface SyncFileStore {

    /** The sync file's content, or null when there is none yet. */
    suspend fun read(): String?

    /** Creates or replaces the sync file. */
    suspend fun write(content: String)
}

/** The account's authorization for Drive is gone or was revoked. */
class DriveAuthException(message: String) : IOException(message)

/** The user's Google storage is full, so Drive accepts no new data. */
class DriveFullException(message: String) : IOException(message)

/**
 * The sync file in the hidden app data folder of the user's Google Drive.
 * Only this app can see that folder, and it does not show in Drive's own
 * interface. The Drive REST API is called directly; [accessToken] supplies
 * a token for the drive.appdata scope. A token Drive refuses is handed to
 * [forgetToken] and the request is tried once more with a fresh one.
 */
class DriveAppDataStore(
    private val accessToken: suspend () -> String,
    private val forgetToken: suspend (String) -> Unit = {},
    private val baseUrl: String = "https://www.googleapis.com",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SyncFileStore {

    @Serializable
    private data class FileList(val files: List<FileRef> = emptyList())

    @Serializable
    private data class FileRef(val id: String)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(): String? {
        val id = findId() ?: return null
        return request("GET", "$baseUrl/drive/v3/files/$id?alt=media")
    }

    override suspend fun write(content: String) {
        val id = findId()
        if (id == null) create(content) else update(id, content)
    }

    private suspend fun findId(): String? {
        val query = URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
        val text = request("GET", "$baseUrl/drive/v3/files?spaces=appDataFolder&q=$query&fields=files(id)")
        return json.decodeFromString(FileList.serializer(), text).files.firstOrNull()?.id
    }

    private suspend fun create(content: String) {
        val boundary = "konsolessh-" + UUID.randomUUID()
        val metadata = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n$content\r\n--$boundary--\r\n"
        request(
            method = "POST",
            url = "$baseUrl/upload/drive/v3/files?uploadType=multipart",
            body = body,
            contentType = "multipart/related; boundary=$boundary",
        )
    }

    // HttpURLConnection has no PATCH; Google's APIs accept it as a POST override.
    private suspend fun update(id: String, content: String) {
        request(
            method = "POST",
            url = "$baseUrl/upload/drive/v3/files/$id?uploadType=media",
            body = content,
            contentType = "application/json",
            override = "PATCH",
        )
    }

    private suspend fun request(
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = null,
        override: String? = null,
    ): String {
        val token = accessToken()
        return try {
            send(token, method, url, body, contentType, override)
        } catch (_: DriveAuthException) {
            forgetToken(token)
            send(accessToken(), method, url, body, contentType, override)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun send(
        token: String,
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        override: String?,
    ): String = withContext(io) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            override?.let { connection.setRequestProperty("X-HTTP-Method-Override", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_UNAUTHORIZED -> throw DriveAuthException("Drive refused the token")
                // Google's error body names the cause (e.g. a disabled API or a missing scope); it holds no user data.
                code !in 200..299 -> {
                    val error = errorBody(connection)
                    if (error.contains(QUOTA_EXCEEDED)) throw DriveFullException("Drive storage is full")
                    throw IOException("Drive answered $code: $error")
                }
                else -> connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun errorBody(connection: HttpURLConnection): String =
        runCatching { connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            ?.take(ERROR_BODY_CHARS).orEmpty()

    companion object {
        const val FILE_NAME = "konsolessh-sync.json"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val TIMEOUT_MS = 30_000
        private const val ERROR_BODY_CHARS = 500
        private const val QUOTA_EXCEEDED = "storageQuotaExceeded"
    }
}
