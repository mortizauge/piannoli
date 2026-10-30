package dev.piannoli.scorza.util

object InputLog {
    private val log = RotatingLogFile("input.log", 512L * 1024, "HH:mm:ss.SSS") { LoggingPrefs.input }

    fun init(piannoliRoot: String) = log.init(piannoliRoot)

    fun write(message: String) = log.write(message)
}
