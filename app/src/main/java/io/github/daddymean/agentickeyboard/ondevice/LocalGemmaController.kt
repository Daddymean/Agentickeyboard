package io.github.daddymean.agentickeyboard.ondevice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.daddymean.agentickeyboard.models.DownloadResult
import io.github.daddymean.agentickeyboard.models.ModelCatalog
import io.github.daddymean.agentickeyboard.models.ModelConsent
import io.github.daddymean.agentickeyboard.models.ModelManager
import io.github.daddymean.agentickeyboard.models.ModelSpec
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wires Hax's shared [ModelManager] to the Gemma 4 E2B catalog entry and the
 * LiteRT engine. The opt-in toggle is off by default ([ModelConsent]).
 * Download also requires a size acknowledgement and an unmetered network
 * unless the user allows cellular.
 *
 * Turning the toggle off stops using the model but does not delete the
 * ~2.59 GB file; [delete] revokes opt-in and removes it.
 */
class LocalGemmaController(
    context: Context,
    private val settings: KeyboardSettings,
    private val scope: CoroutineScope,
    private val manager: ModelManager = ModelManager.forApp(context),
    private val consent: ModelConsent = ModelConsent(context),
    cacheDir: File = File(context.applicationContext.cacheDir, "litert-lm")
) {
    val artifact: ModelSpec = ModelCatalog.GEMMA_4_E2B

    val engine: GemmaLitertEngine = GemmaLitertEngine(
        modelFile = { manager.verifiedFile(artifact) },
        cacheDir = cacheDir,
        enabled = { isEnabled() && isReady() }
    )

    private val _storeState = MutableStateFlow<GemmaStoreState>(
        if (isReady()) GemmaStoreState.Ready else GemmaStoreState.Idle
    )
    val storeState: StateFlow<GemmaStoreState> = _storeState.asStateFlow()

    val asOnDeviceAi: OnDeviceAi get() = engine

    init {
        engine.refreshAvailability(isEnabled() && isReady())
    }

    fun isEnabled(): Boolean = manager.isOptedIn(artifact.id)

    fun isReady(): Boolean = manager.isReady(artifact)

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            manager.setOptedIn(artifact, true)
        } else {
            // Keep the downloaded file; [delete] is the explicit removal path.
            consent.setOptedIn(artifact.id, false)
            engine.close()
        }
        engine.refreshAvailability(isEnabled() && isReady())
        _storeState.value = if (isReady()) GemmaStoreState.Ready else GemmaStoreState.Idle
    }

    fun isUnmetered(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun requestDownload(context: Context, allowMetered: Boolean = settings.isLocalGemmaCellularAllowed) {
        settings.isLocalGemmaSizeAcknowledged = true
        if (!isEnabled()) manager.setOptedIn(artifact, true)
        val decision = ModelDownloadPolicy.decide(
            ModelDownloadPolicy.Request(
                userOptedIn = isEnabled(),
                sizeAcknowledged = settings.isLocalGemmaSizeAcknowledged,
                unmeteredNetwork = isUnmetered(context),
                allowMetered = allowMetered,
                alreadyReady = isReady(),
                alreadyInProgress = _storeState.value is GemmaStoreState.Downloading ||
                    _storeState.value is GemmaStoreState.Verifying
            )
        )
        when (decision) {
            ModelDownloadPolicy.Decision.AlreadyReady -> {
                _storeState.value = GemmaStoreState.Ready
                engine.refreshAvailability(true)
                return
            }
            ModelDownloadPolicy.Decision.InProgress -> return
            is ModelDownloadPolicy.Decision.Refuse -> {
                _storeState.value = GemmaStoreState.Refused(decision.reason)
                return
            }
            ModelDownloadPolicy.Decision.Start -> Unit
        }
        _storeState.value = GemmaStoreState.Downloading(0L, artifact.sizeBytes)
        scope.launch {
            val result = withContext(Dispatchers.IO) { manager.download(artifact) }
            _storeState.value = when (result) {
                is DownloadResult.Ready -> GemmaStoreState.Ready
                DownloadResult.NotOptedIn -> GemmaStoreState.Refused(ModelDownloadPolicy.Reason.OPT_IN_REQUIRED)
                is DownloadResult.HashMismatch -> {
                    GemmaStoreState.Failed("SHA-256 mismatch; the file was deleted.")
                }
                is DownloadResult.Failed -> GemmaStoreState.Failed(result.message)
            }
            engine.refreshAvailability(isEnabled() && isReady())
        }
    }

    fun delete() {
        engine.close()
        manager.setOptedIn(artifact, false)
        engine.refreshAvailability(false)
        _storeState.value = GemmaStoreState.Idle
    }

    fun onEnabledChanged() {
        engine.refreshAvailability(isEnabled() && isReady())
    }

    companion object {
        fun formattedSize(spec: ModelSpec): String {
            val gb = spec.sizeBytes / 1_000_000_000.0
            val bytes = spec.sizeBytes.toString().reversed().chunked(3).joinToString(",").reversed()
            return if (gb >= 1.0) {
                String.format(Locale.US, "%.2f GB (%s bytes)", gb, bytes)
            } else {
                val mb = spec.sizeBytes / 1_000_000.0
                String.format(Locale.US, "%.0f MB (%s bytes)", mb, bytes)
            }
        }
    }
}

sealed class GemmaStoreState {
    data object Idle : GemmaStoreState()
    data class Downloading(val bytes: Long, val total: Long) : GemmaStoreState()
    data object Verifying : GemmaStoreState()
    data object Ready : GemmaStoreState()
    data class Failed(val message: String) : GemmaStoreState()
    data class Refused(val reason: ModelDownloadPolicy.Reason) : GemmaStoreState()
}
