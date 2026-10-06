package com.weeklight

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks.readonly"

internal enum class TaskState { READY, RECONNECT, OFFLINE, UNAVAILABLE }
internal data class TaskAccess(val token: String? = null, val state: TaskState)

internal object GoogleTasksAuthorization {
    fun request(account: String): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setAccount(Account(account, "com.google"))
            .setRequestedScopes(listOf(Scope(TASKS_SCOPE)))
            .build()

    // This path never launches a resolution. Only MainActivity may show authorization UI.
    suspend fun silentAccess(context: Context, account: String): TaskAccess =
        suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(context).authorize(request(account))
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(
                        if (result.hasResolution() || result.accessToken.isNullOrBlank()) TaskAccess(state = TaskState.RECONNECT)
                        else TaskAccess(result.accessToken, TaskState.READY),
                    )
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(TaskAccess(state = TaskState.UNAVAILABLE))
                }
        }
}
