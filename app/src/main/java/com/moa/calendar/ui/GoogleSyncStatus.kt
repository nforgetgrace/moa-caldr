package com.moa.calendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.moa.calendar.data.DeviceCalendars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val GOOGLE_SYNC_STATUS_POLL_MS = 2_000L

@Composable
internal fun rememberGoogleSyncActive(
    resumed: Boolean,
    accountNames: List<String>,
    statusRevision: Int,
): Boolean {
    val readActive = defaultGoogleSyncReader()
    return rememberGoogleSyncActive(resumed, accountNames, statusRevision, readActive)
}

@Composable
internal fun rememberGoogleSyncActive(
    resumed: Boolean,
    accountNames: List<String>,
    statusRevision: Int,
    readActive: suspend (List<String>) -> Boolean,
): Boolean {
    val accounts = accountNames.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    val latestReadActive by rememberUpdatedState(readActive)
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(resumed, statusRevision, accounts) {
        if (!resumed || accounts.isEmpty()) {
            active = false
            return@LaunchedEffect
        }
        while (isActive) {
            active = try {
                latestReadActive(accounts)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            delay(GOOGLE_SYNC_STATUS_POLL_MS)
        }
    }
    return active
}

@Composable
private fun defaultGoogleSyncReader(): suspend (List<String>) -> Boolean {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        { accounts -> withContext(Dispatchers.IO) { DeviceCalendars(context).isGoogleSyncActive(accounts) } }
    }
}
