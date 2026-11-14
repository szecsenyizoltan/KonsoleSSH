package hu.billman.konsolessh.sync

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Access tokens for the drive.appdata scope through Google Play services.
 * Once the user has granted the scope, a token comes without any UI; the
 * first time, and after the grant is revoked, the user has to confirm in
 * Google's own screen, which [ConsentNeededException] carries.
 */
class DriveAuthorizer(context: Context) {

    /** The user has to confirm access in Google's screen, started from [intent]. */
    class ConsentNeededException(val intent: PendingIntent) : IOException("Drive access needs the user's consent")

    private val client = Identity.getAuthorizationClient(context.applicationContext)

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DriveAppDataStore.SCOPE)))
        .build()

    suspend fun accessToken(): String {
        val result = client.authorize(request).await()
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) throw ConsentNeededException(pending)
        return result.accessToken ?: throw IOException("Google Play services returned no token")
    }

    /** The token that Drive refused, so the next request fetches a fresh one. */
    suspend fun forget(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    /** The result of the consent screen, as the activity received it. */
    fun tokenFrom(data: android.content.Intent?): String? =
        runCatching { client.getAuthorizationResultFromIntent(data).accessToken }.getOrNull()
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
