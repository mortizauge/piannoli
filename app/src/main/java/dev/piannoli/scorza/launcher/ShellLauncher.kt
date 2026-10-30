package dev.piannoli.scorza.launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShellLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    var debugLog: (String) -> Unit = {}

    fun launch(argv: List<String>): LaunchResult {
        debugLog("ShellLauncher.launch ${argv.joinToString(" ")}")
        return try {
            val proc = ProcessBuilder(argv).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            debugLog("  exit=$exit output=${output.trim()}")
            if (exit == 0) LaunchResult.Success
            else LaunchResult.Error("am start failed (exit $exit): ${output.trim()}")
        } catch (e: Exception) {
            debugLog("  exception: ${e.javaClass.simpleName}: ${e.message}")
            LaunchResult.Error(e.message ?: "Failed to spawn am start")
        }
    }
}
