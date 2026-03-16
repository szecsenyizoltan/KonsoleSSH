package hu.szecsenyi.konsolessh.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM unit tests — no Android runtime needed.
 * Tests ConnectionConfig value logic.
 */
class ConnectionConfigTest {

    // ---- displayName() ----

    @Test
    fun `displayName returns name when name is set`() {
        val config = ConnectionConfig(name = "Production", host = "10.0.0.1", username = "root")
        assertEquals("Production", config.displayName())
    }

    @Test
    fun `displayName returns user@host when name is blank`() {
        val config = ConnectionConfig(name = "", host = "myserver.hu", username = "admin")
        assertEquals("admin@myserver.hu", config.displayName())
    }

    @Test
    fun `displayName returns user@host when name is whitespace only`() {
        val config = ConnectionConfig(name = "   ", host = "srv.example.com", username = "deploy")
        assertEquals("deploy@srv.example.com", config.displayName())
    }

    @Test
    fun `displayName returns user@host when both empty`() {
        val config = ConnectionConfig(name = "", host = "", username = "")
        assertEquals("@", config.displayName())
    }

    // ---- Default values ----

    @Test
    fun `default port is 22`() {
        val config = ConnectionConfig(host = "host", username = "user")
        assertEquals(22, config.port)
    }

    @Test
    fun `default authType is PASSWORD`() {
        val config = ConnectionConfig(host = "host", username = "user")
        assertEquals(ConnectionConfig.AuthType.PASSWORD, config.authType)
    }

    @Test
    fun `default password and privateKey are empty strings`() {
        val config = ConnectionConfig(host = "host", username = "user")
        assertEquals("", config.password)
        assertEquals("", config.privateKey)
        assertEquals("", config.privateKeyPassphrase)
    }

    @Test
    fun `each instance gets unique id by default`() {
        val a = ConnectionConfig(host = "a", username = "u")
        val b = ConnectionConfig(host = "b", username = "u")
        assertNotEquals(a.id, b.id)
    }

    // ---- AuthType enum ----

    @Test
    fun `AuthType PRIVATE_KEY can be set`() {
        val config = ConnectionConfig(
            host = "host",
            username = "user",
            authType = ConnectionConfig.AuthType.PRIVATE_KEY,
            privateKey = "-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----"
        )
        assertEquals(ConnectionConfig.AuthType.PRIVATE_KEY, config.authType)
        assertTrue(config.privateKey.startsWith("-----BEGIN"))
    }

    // ---- Port bounds ----

    @Test
    fun `non-standard port is stored as-is`() {
        val config = ConnectionConfig(host = "host", username = "user", port = 2222)
        assertEquals(2222, config.port)
    }

    @Test
    fun `port 443 is accepted`() {
        val config = ConnectionConfig(host = "host", username = "user", port = 443)
        assertEquals(443, config.port)
    }

    // ---- Data class equality ----

    @Test
    fun `two configs with same id and fields are equal`() {
        val id = "fixed-id-1234"
        val a = ConnectionConfig(id = id, name = "Test", host = "h", username = "u")
        val b = ConnectionConfig(id = id, name = "Test", host = "h", username = "u")
        assertEquals(a, b)
    }

    @Test
    fun `configs with different id are not equal`() {
        val a = ConnectionConfig(id = "id-1", host = "h", username = "u")
        val b = ConnectionConfig(id = "id-2", host = "h", username = "u")
        assertNotEquals(a, b)
    }

    // ---- Copy ----

    @Test
    fun `copy preserves id and overrides fields`() {
        val original = ConnectionConfig(id = "abc", host = "old.host", username = "user")
        val updated = original.copy(host = "new.host")
        assertEquals("abc", updated.id)
        assertEquals("new.host", updated.host)
        assertEquals("user", updated.username)
    }
}
