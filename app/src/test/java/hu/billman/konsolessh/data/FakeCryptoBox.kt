package hu.billman.konsolessh.data

/**
 * Tesztekhez szánt CryptoBox-impl. Nem valódi titkosítás — determinista
 * mask (XOR egy konstans kulccsal) + magic-prefix, hogy a tesztek lássák
 * a különbséget a nyers és a "titkosított" bájtok között, de a
 * decrypt(encrypt(x)) == x kontraktus teljesüljön.
 */
class FakeCryptoBox(
    private val maskKey: Byte = 0x5A,
) : CryptoBox {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + MAGIC.size)
        MAGIC.copyInto(out, 0)
        for (i in plaintext.indices) {
            out[MAGIC.size + i] = (plaintext[i].toInt() xor maskKey.toInt()).toByte()
        }
        return out
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size >= MAGIC.size) { "payload too short" }
        for (i in MAGIC.indices) {
            require(payload[i] == MAGIC[i]) { "bad magic at index $i" }
        }
        val out = ByteArray(payload.size - MAGIC.size)
        for (i in out.indices) {
            out[i] = (payload[MAGIC.size + i].toInt() xor maskKey.toInt()).toByte()
        }
        return out
    }

    companion object {
        private val MAGIC = byteArrayOf(0x46, 0x41, 0x4B, 0x45) // "FAKE"
    }
}
