package hu.billman.konsolessh.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces the file's content so that a crash or power loss leaves either
 * the old content or the new one, never a truncated file.
 *
 * The bytes go to `<file>.tmp`, are synced to the disk, and the temporary
 * file is then moved over the target in one atomic rename. Without the sync
 * the rename can reach the disk before the data, and a power loss leaves an
 * empty file under the old name. A failed rename throws and leaves the
 * target as it was; it is not retried as a direct write, which would lose
 * exactly this guarantee.
 */
fun File.writeAtomically(bytes: ByteArray) {
    parentFile?.mkdirs()
    val tmp = File("$path.tmp")
    try {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
}
