package dev.piannoli.scorza.launcher

import dev.piannoli.scorza.model.Rom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LaunchState @Inject constructor() {
    @Volatile var launching: Boolean = false
    @Volatile var lastLaunched: Rom? = null
}
