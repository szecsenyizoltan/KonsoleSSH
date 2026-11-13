package hu.billman.konsolessh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAddressTest {

    @Test
    fun `RFC 1918 addresses are private`() {
        listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.10", " 192.168.0.1 ")
            .forEach { assertTrue(it, PrivateAddress.isPrivate(it)) }
    }

    @Test
    fun `public addresses that share a first octet are not private`() {
        listOf("172.15.0.1", "172.32.0.1", "172.217.16.14", "192.0.2.1", "192.169.0.1", "11.0.0.1")
            .forEach { assertFalse(it, PrivateAddress.isPrivate(it)) }
    }

    @Test
    fun `recognised while typing once the deciding octets are complete`() {
        listOf("10.", "10.1", "172.16.", "172.20.", "192.168", "192.168.")
            .forEach { assertTrue(it, PrivateAddress.isPrivate(it)) }
        listOf("1", "10", "172.", "172.2", "172.20", "192.", "192.16")
            .forEach { assertFalse(it, PrivateAddress.isPrivate(it)) }
    }

    @Test
    fun `host names and malformed input are not private`() {
        listOf("", "db.internal", "10.example.com", "10.0.0.300.x", "fe80::1", "192.168.0.1:22")
            .forEach { assertFalse(it, PrivateAddress.isPrivate(it)) }
    }
}
