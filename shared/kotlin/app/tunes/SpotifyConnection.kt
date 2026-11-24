package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val LocalSpotifyConnection = staticCompositionLocalOf<SpotifyConnection?> { null }

/** The browser handshake survives closing the import dialog, but ends with the app's UI scope. */
internal class SpotifyConnection(
    val account: SpotifyAccount,
    private val scope: CoroutineScope,
    private val authorize: suspend ((String) -> SpotifyAuthorization, (String) -> Unit) -> String = SpotifyOAuthLoopback::authorize,
) {
    var connecting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun connect(clientId: String, openBrowser: (String) -> Unit) {
        if (connecting) return
        connecting = true
        error = null
        job = scope.launch {
            var completed = false
            try {
                val callback = authorize({ redirect -> account.beginAuthorization(clientId.trim(), redirect) }, openBrowser)
                account.completeAuthorization(callback)
                completed = true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.take(300) ?: "Couldn't connect to Spotify. Please try again."
            } finally {
                if (!completed) account.cancelAuthorization()
                connecting = false
                job = null
            }
        }
    }

    fun cancel() { job?.cancel() }
    fun disconnect() { cancel(); account.disconnect(); error = null }
}
