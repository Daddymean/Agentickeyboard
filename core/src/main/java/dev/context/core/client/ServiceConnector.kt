package dev.context.core.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import dev.context.IContextService
import dev.context.core.ContextContract
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** The service could not be reached right now; retryable. */
class ServiceUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Lazily binds to IContextService, shares the binding between concurrent
 * calls, and unbinds after [idleUnbindMs] without calls. Holding a binding
 * pins :context-app at the client's process importance (an IME is
 * perceptible), so idle unbinding is what keeps it from staying resident.
 *
 * Only binds to a service that declares [ContextContract.PERMISSION] and is
 * signed with this app's certificate: resolving by action alone would let any
 * installed app intercept the events (including keyboard notes).
 */
internal class ServiceConnector(
  context: Context,
  private val scope: CoroutineScope,
  private val servicePackage: String?,
  private val bindTimeoutMs: Long,
  private val idleUnbindMs: Long,
) {
  private val appContext = context.applicationContext
  private val lock = Any()
  private var connection: Connection? = null
  private var ready = CompletableDeferred<IContextService>()
  private var current: IContextService? = null
  private var active = 0
  private var idleJob: Job? = null
  private var closed = false

  /** Runs [block] against the bound service on [Dispatchers.IO]. */
  suspend fun <T> call(block: (IContextService) -> T): T {
    val service = acquire()
    try {
      return withContext(Dispatchers.IO) { block(service) }
    } catch (e: DeadObjectException) {
      markDisconnected(service)
      throw e
    } finally {
      release()
    }
  }

  fun close() {
    synchronized(lock) {
      closed = true
      idleJob?.cancel()
      unbindLocked()
    }
  }

  private suspend fun acquire(): IContextService {
    val deferred = synchronized(lock) {
      if (closed) throw ServiceUnavailableException("client closed")
      idleJob?.cancel()
      idleJob = null
      active++
      try {
        if (connection == null) bindLocked()
      } catch (t: Throwable) {
        active--
        throw t
      }
      ready
    }
    return try {
      withTimeout(bindTimeoutMs) { deferred.await() }
    } catch (e: TimeoutCancellationException) {
      release()
      throw ServiceUnavailableException("bind timed out after ${bindTimeoutMs}ms", e)
    } catch (t: Throwable) {
      release()
      throw t
    }
  }

  private fun release() {
    synchronized(lock) {
      active--
      if (active > 0 || connection == null || closed) return
      idleJob?.cancel()
      idleJob = scope.launch {
        delay(idleUnbindMs)
        synchronized(lock) { if (active == 0) unbindLocked() }
      }
    }
  }

  private fun bindLocked() {
    val component = resolveTrustedService()
      ?: throw ServiceUnavailableException("no trusted ${ContextContract.ACTION_BIND} service installed")
    val conn = Connection()
    ready = CompletableDeferred()
    // SecurityException (permission not granted) propagates: it is not retryable.
    val bound = appContext.bindService(Intent(ContextContract.ACTION_BIND).setComponent(component), conn, Context.BIND_AUTO_CREATE)
    if (!bound) {
      // bindService docs: unbind even when it returned false.
      runCatching { appContext.unbindService(conn) }
      throw ServiceUnavailableException("bindService($component) returned false")
    }
    connection = conn
  }

  private fun unbindLocked() {
    val conn = connection ?: return
    connection = null
    current = null
    runCatching { appContext.unbindService(conn) }
    if (!ready.isCompleted) ready.completeExceptionally(ServiceUnavailableException("unbound"))
    ready = CompletableDeferred()
  }

  private fun markDisconnected(service: IContextService) {
    synchronized(lock) {
      // The system reconnects a BIND_AUTO_CREATE binding by itself; just stop
      // handing out the dead proxy until onServiceConnected fires again.
      if (current === service) {
        current = null
        if (ready.isCompleted) ready = CompletableDeferred()
      }
    }
  }

  private fun resolveTrustedService(): ComponentName? {
    val pm = appContext.packageManager
    val intent = Intent(ContextContract.ACTION_BIND)
    if (servicePackage != null) intent.setPackage(servicePackage)
    return pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
      .asSequence()
      .mapNotNull { it.serviceInfo }
      .filter { it.exported && it.permission == ContextContract.PERMISSION }
      .firstOrNull { pm.checkSignatures(appContext.packageName, it.packageName) == PackageManager.SIGNATURE_MATCH }
      ?.let { ComponentName(it.packageName, it.name) }
  }

  private inner class Connection : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      synchronized(lock) {
        if (connection !== this) return
        val service = IContextService.Stub.asInterface(binder)
        if (ready.isCompleted) ready = CompletableDeferred()
        current = service
        ready.complete(service)
      }
    }

    override fun onServiceDisconnected(name: ComponentName) {
      synchronized(lock) {
        if (connection !== this) return
        current = null
        if (ready.isCompleted) ready = CompletableDeferred()
      }
    }

    override fun onBindingDied(name: ComponentName) {
      // The binding will never reconnect (e.g. the service app was updated).
      synchronized(lock) {
        if (connection !== this) return
        val pending = ready
        unbindLocked()
        pending.completeExceptionally(ServiceUnavailableException("binding died"))
      }
    }

    override fun onNullBinding(name: ComponentName) {
      synchronized(lock) {
        if (connection !== this) return
        val pending = ready
        unbindLocked()
        pending.completeExceptionally(ServiceUnavailableException("service returned a null binder"))
      }
    }
  }
}
