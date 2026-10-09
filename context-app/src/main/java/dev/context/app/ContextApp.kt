package dev.context.app

import android.app.Application
import android.content.Context
import dev.context.app.work.Schedules

/** Process entry point: owns the [AppGraph] and makes sure background work is scheduled. */
class ContextApp : Application() {
  val graph: AppGraph by lazy { AppGraph(this) }

  override fun onCreate() {
    super.onCreate()
    Schedules.ensure(this)
  }
}

/** The process-wide [AppGraph], reachable from any component's context. */
val Context.graph: AppGraph
  get() = (applicationContext as ContextApp).graph
