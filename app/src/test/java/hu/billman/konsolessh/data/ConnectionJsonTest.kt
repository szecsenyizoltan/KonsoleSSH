package hu.billman.konsolessh.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.model.ConnectionConfig.AuthType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Versions up to 1.1.30 stored the connection list with Gson. These tests
 * pin that the kotlinx.serialization format reads what Gson wrote, and that
 * Gson (an older version after a downgrade) reads what this version writes.
 */
class ConnectionJsonTest {

    private val gson = Gson()
    private val gsonListType = object : TypeToken<List<ConnectionConfig>>() {}.type

    private val full = ConnectionConfig(
        id = "3f1c",
        name = "Prod <db> & 'backup' = \"main\"",
        host = "db.example.org",
        port = 2222,
        username = "root",
        authType = AuthType.PRIVATE_KEY,
        password = "p=w<>&'\"\\ árvíztűrő",
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc==\n-----END OPENSSH PRIVATE KEY-----\n",
        privateKeyPassphrase = "secret",
        jumpConnectionId = "j1",
        jumpHost = "bastion.example.org",
        jumpPort = 2200,
        jumpUsername = "jump",
        jumpPassword = "jp",
    )
    private val plain = ConnectionConfig(id = "a", host = "10.0.0.1", username = "pi")

    @Test
    fun `reads what Gson wrote`() {
        val written = gson.toJson(listOf(full, plain))
        assertEquals(listOf(full, plain), ConnectionJson.decode(written))
    }

    @Test
    fun `Gson reads what this version writes`() {
        val written = ConnectionJson.encode(listOf(full, plain))
        assertEquals(listOf(full, plain), gson.fromJson<List<ConnectionConfig>>(written, gsonListType))
    }

    @Test
    fun `writes every field, defaults included, in Gson's order`() {
        val written = ConnectionJson.encode(listOf(plain))
        val keys = Regex("\"(\\w+)\":").findAll(written).map { it.groupValues[1] }.toList()
        // updatedAt (2.2.0) comes last; older versions skip it as an unknown key.
        assertEquals(
            listOf(
                "id", "name", "host", "port", "username", "authType", "password", "privateKey",
                "privateKeyPassphrase", "jumpConnectionId", "jumpHost", "jumpPort", "jumpUsername",
                "jumpPassword", "updatedAt",
            ),
            keys,
        )
    }

    @Test
    fun `Gson's HTML escaping is read back as the original characters`() {
        // Gson escapes = < > & ' by default; this is a literal store fragment.
        val stored = """[{"id":"x","name":"a=b <c> & 'd'","host":"h","port":22,""" +
            """"username":"u","authType":"PASSWORD","password":"","privateKey":"","privateKeyPassphrase":""}]"""
        assertEquals("a=b <c> & 'd'", ConnectionJson.decode(stored).single().name)
    }

    @Test
    fun `a store from before jump hosts gets the defaults for the missing fields`() {
        val stored = """[{"id":"old","name":"Old","host":"h","port":22,"username":"u",""" +
            """"authType":"PASSWORD","password":"pw","privateKey":"","privateKeyPassphrase":""}]"""
        val config = ConnectionJson.decode(stored).single()
        assertEquals(ConnectionConfig(id = "old", name = "Old", host = "h", username = "u", password = "pw"), config)
        assertEquals(22, config.jumpPort)
    }

    @Test
    fun `a field this version does not know is skipped`() {
        val stored = """[{"id":"n","host":"h","username":"u","colorTag":"red","port":22}]"""
        assertEquals(ConnectionConfig(id = "n", host = "h", username = "u"), ConnectionJson.decode(stored).single())
    }

    @Test
    fun `null and unknown enum values fall back to the defaults`() {
        val stored = """[{"id":"n","host":"h","username":"u","password":null,"authType":"FIDO2"}]"""
        val config = ConnectionJson.decode(stored).single()
        assertEquals("", config.password)
        assertEquals(AuthType.PASSWORD, config.authType)
    }

    @Test
    fun `an empty store is an empty list`() {
        assertTrue(ConnectionJson.decode("[]").isEmpty())
    }
}
