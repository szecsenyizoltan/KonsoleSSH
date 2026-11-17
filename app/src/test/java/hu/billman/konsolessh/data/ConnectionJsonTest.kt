package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.model.ConnectionConfig.Auth
import hu.billman.konsolessh.model.ConnectionConfig.Jump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stored connection format: the field set and order, and how the
 * decoder treats escaped, missing, unknown and null fields. Older 2.x
 * devices read the same format from the sync file.
 */
class ConnectionJsonTest {

    private val full = ConnectionConfig(
        id = "3f1c",
        name = "Prod <db> & 'backup' = \"main\"",
        host = "db.example.org",
        port = 2222,
        username = "root",
        auth = Auth.PrivateKey(
            pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc==\n-----END OPENSSH PRIVATE KEY-----\n",
            passphrase = "p=w<>&'\"\\ árvíztűrő",
        ),
        jump = Jump.Saved("j1"),
    )
    private val manualJump = ConnectionConfig(
        id = "m",
        host = "10.0.0.2",
        jump = Jump.Manual("bastion.example.org", port = 2200, username = "jump", password = "jp"),
    )
    private val plain = ConnectionConfig(id = "a", host = "10.0.0.1", username = "pi", auth = Auth.Password("p=w<>&'"))

    @Test
    fun `reads back what it writes`() {
        val written = ConnectionJson.encode(listOf(full, plain, manualJump))
        assertEquals(listOf(full, plain, manualJump), ConnectionJson.decode(written))
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
        val expected = ConnectionConfig(
            id = "old",
            name = "Old",
            host = "h",
            username = "u",
            auth = Auth.Password("pw"),
        )
        assertEquals(expected, config)
        assertEquals(Jump.None, config.jump)
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
        assertEquals(Auth.Password(""), config.auth)
    }

    @Test
    fun `of two stored secrets the chosen method's is read`() {
        val both = """"password":"pw","privateKey":"KEY","privateKeyPassphrase":"pp""""
        val asPassword = """[{"id":"n","host":"h","authType":"PASSWORD",$both}]"""
        val asKey = """[{"id":"n","host":"h","authType":"PRIVATE_KEY",$both}]"""
        assertEquals(Auth.Password("pw"), ConnectionJson.decode(asPassword).single().auth)
        assertEquals(Auth.PrivateKey("KEY", "pp"), ConnectionJson.decode(asKey).single().auth)
    }

    @Test
    fun `an old profile without its chosen secret uses the one it has`() {
        val keyOnly = """[{"id":"n","host":"h","authType":"PASSWORD","password":"","privateKey":"KEY"}]"""
        val passwordOnly = """[{"id":"n","host":"h","authType":"PRIVATE_KEY","password":"pw","privateKey":""}]"""
        val neither = """[{"id":"n","host":"h","authType":"PRIVATE_KEY"}]"""
        assertEquals(Auth.PrivateKey("KEY"), ConnectionJson.decode(keyOnly).single().auth)
        assertEquals(Auth.Password("pw"), ConnectionJson.decode(passwordOnly).single().auth)
        assertEquals(Auth.Password(""), ConnectionJson.decode(neither).single().auth)
    }

    @Test
    fun `only the chosen method's secrets are written`() {
        val written = ConnectionJson.encode(listOf(full))
        assertTrue(written.contains(""""authType":"PRIVATE_KEY","password":"","""))
    }

    @Test
    fun `a saved jump host wins over leftover manual jump fields`() {
        val stored = """[{"id":"n","host":"h","jumpConnectionId":"gw","jumpHost":"old.example","jumpPort":2200}]"""
        assertEquals(Jump.Saved("gw"), ConnectionJson.decode(stored).single().jump)
    }

    @Test
    fun `an empty store is an empty list`() {
        assertTrue(ConnectionJson.decode("[]").isEmpty())
    }
}
