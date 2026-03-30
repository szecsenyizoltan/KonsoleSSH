package hu.szecsenyi.konsolessh.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric unit tests for SavedConnections.
 * Uses a real (in-memory) SharedPreferences via Robolectric context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedConnectionsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any leftover data before each test
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    private fun clearPrefs() {
        context.getSharedPreferences("konsole_connections", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("konsole_connections_enc", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ---- load ----

    @Test
    fun `load returns empty list when no data stored`() {
        val result = SavedConnections.load(context)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `load returns mutable list`() {
        val result = SavedConnections.load(context)
        result.add(ConnectionConfig(host = "x", username = "u"))
        assertEquals(1, result.size) // just verifies mutability
    }

    // ---- save + load roundtrip ----

    @Test
    fun `save and load roundtrip preserves all fields`() {
        val config = ConnectionConfig(
            id = "fixed-id",
            name = "Prod",
            host = "10.0.0.1",
            port = 2222,
            username = "root",
            authType = ConnectionConfig.AuthType.PASSWORD,
            password = "secret"
        )
        SavedConnections.save(context, listOf(config))

        val loaded = SavedConnections.load(context)
        assertEquals(1, loaded.size)
        val c = loaded[0]
        assertEquals("fixed-id", c.id)
        assertEquals("Prod", c.name)
        assertEquals("10.0.0.1", c.host)
        assertEquals(2222, c.port)
        assertEquals("root", c.username)
        assertEquals(ConnectionConfig.AuthType.PASSWORD, c.authType)
        assertEquals("secret", c.password)
    }

    @Test
    fun `save empty list clears stored data`() {
        SavedConnections.save(context, listOf(
            ConnectionConfig(host = "h", username = "u")
        ))
        SavedConnections.save(context, emptyList())
        val loaded = SavedConnections.load(context)
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `save multiple configs and load all`() {
        val configs = listOf(
            ConnectionConfig(id = "1", host = "h1", username = "u1"),
            ConnectionConfig(id = "2", host = "h2", username = "u2"),
            ConnectionConfig(id = "3", host = "h3", username = "u3")
        )
        SavedConnections.save(context, configs)
        val loaded = SavedConnections.load(context)
        assertEquals(3, loaded.size)
        assertEquals("h1", loaded[0].host)
        assertEquals("h2", loaded[1].host)
        assertEquals("h3", loaded[2].host)
    }

    // ---- saveOne ----

    @Test
    fun `saveOne adds new config when id not present`() {
        val config = ConnectionConfig(id = "new-1", host = "host", username = "user")
        SavedConnections.saveOne(context, config)
        val loaded = SavedConnections.load(context)
        assertEquals(1, loaded.size)
        assertEquals("new-1", loaded[0].id)
    }

    @Test
    fun `saveOne updates existing config with same id`() {
        val original = ConnectionConfig(id = "same-id", host = "old.host", username = "user")
        SavedConnections.saveOne(context, original)

        val updated = original.copy(host = "new.host", password = "newpass")
        SavedConnections.saveOne(context, updated)

        val loaded = SavedConnections.load(context)
        assertEquals(1, loaded.size)
        assertEquals("new.host", loaded[0].host)
        assertEquals("newpass", loaded[0].password)
    }

    @Test
    fun `saveOne appends when different ids`() {
        SavedConnections.saveOne(context, ConnectionConfig(id = "a", host = "h1", username = "u"))
        SavedConnections.saveOne(context, ConnectionConfig(id = "b", host = "h2", username = "u"))
        SavedConnections.saveOne(context, ConnectionConfig(id = "c", host = "h3", username = "u"))
        val loaded = SavedConnections.load(context)
        assertEquals(3, loaded.size)
    }

    @Test
    fun `saveOne update preserves position in list`() {
        SavedConnections.saveOne(context, ConnectionConfig(id = "first", host = "h1", username = "u"))
        SavedConnections.saveOne(context, ConnectionConfig(id = "second", host = "h2", username = "u"))
        SavedConnections.saveOne(context, ConnectionConfig(id = "third", host = "h3", username = "u"))

        // Update the middle one — host "h2b" stays alphabetically between "h1" and "h3"
        SavedConnections.saveOne(context, ConnectionConfig(id = "second", host = "h2b", username = "u"))

        val loaded = SavedConnections.load(context)
        assertEquals(3, loaded.size)
        assertEquals("first",   loaded[0].id)
        assertEquals("second",  loaded[1].id)
        assertEquals("h2b",     loaded[1].host)
        assertEquals("third",   loaded[2].id)
    }

    // ---- delete ----

    @Test
    fun `delete removes item with matching id`() {
        SavedConnections.save(context, listOf(
            ConnectionConfig(id = "keep-1", host = "h1", username = "u"),
            ConnectionConfig(id = "remove", host = "h2", username = "u"),
            ConnectionConfig(id = "keep-2", host = "h3", username = "u")
        ))
        SavedConnections.delete(context, "remove")
        val loaded = SavedConnections.load(context)
        assertEquals(2, loaded.size)
        assertTrue(loaded.none { it.id == "remove" })
        assertEquals("keep-1", loaded[0].id)
        assertEquals("keep-2", loaded[1].id)
    }

    @Test
    fun `delete with non-existent id does not change list`() {
        SavedConnections.saveOne(context, ConnectionConfig(id = "only", host = "h", username = "u"))
        SavedConnections.delete(context, "ghost-id")
        val loaded = SavedConnections.load(context)
        assertEquals(1, loaded.size)
        assertEquals("only", loaded[0].id)
    }

    @Test
    fun `delete on empty list does not crash`() {
        SavedConnections.delete(context, "anything")
        val loaded = SavedConnections.load(context)
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `delete all items one by one results in empty list`() {
        SavedConnections.save(context, listOf(
            ConnectionConfig(id = "a", host = "h1", username = "u"),
            ConnectionConfig(id = "b", host = "h2", username = "u")
        ))
        SavedConnections.delete(context, "a")
        SavedConnections.delete(context, "b")
        assertTrue(SavedConnections.load(context).isEmpty())
    }

    // ---- Corrupted data ----

    @Test
    fun `load with corrupted JSON returns empty list`() {
        context.getSharedPreferences("konsole_connections", Context.MODE_PRIVATE)
            .edit()
            .putString("connections", "{{INVALID JSON{{")
            .commit()
        val loaded = SavedConnections.load(context)
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `load with empty JSON array returns empty list`() {
        context.getSharedPreferences("konsole_connections", Context.MODE_PRIVATE)
            .edit()
            .putString("connections", "[]")
            .commit()
        val loaded = SavedConnections.load(context)
        assertTrue(loaded.isEmpty())
    }

    // ---- Private key auth type ----

    @Test
    fun `private key auth type is preserved through roundtrip`() {
        val config = ConnectionConfig(
            id = "pk-test",
            host = "server.hu",
            username = "deploy",
            authType = ConnectionConfig.AuthType.PRIVATE_KEY,
            privateKey = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIB...\n-----END RSA PRIVATE KEY-----",
            privateKeyPassphrase = "mypassphrase"
        )
        SavedConnections.saveOne(context, config)
        val loaded = SavedConnections.load(context)[0]
        assertEquals(ConnectionConfig.AuthType.PRIVATE_KEY, loaded.authType)
        assertTrue(loaded.privateKey.startsWith("-----BEGIN"))
        assertEquals("mypassphrase", loaded.privateKeyPassphrase)
    }
}
