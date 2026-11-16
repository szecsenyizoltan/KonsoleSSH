package hu.billman.konsolessh.sync

import android.app.PendingIntent
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncWorkerTest {

    @Test
    fun `network and Drive errors are tried again later`() {
        assertTrue(SyncWorker.retries(IOException("timeout")))
        assertTrue(SyncWorker.retries(DriveAuthException("token expired")))
    }

    @Test
    fun `what needs the user is not retried in the background`() {
        assertFalse(SyncWorker.retries(DriveAuthorizer.ConsentNeededException(mockk<PendingIntent>())))
        assertFalse(SyncWorker.retries(DriveFullException("storageQuotaExceeded")))
        assertFalse(SyncWorker.retries(SyncCipher.WrongPassphraseException()))
        assertFalse(SyncWorker.retries(SyncCipher.UnreadableFileException("bad envelope")))
    }
}
