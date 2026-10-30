package dev.piannoli.scorza.util

object RommLog {
    private val log = RotatingLogFile("romm.log", 1L * 1024 * 1024, "yyyy-MM-dd HH:mm:ss") { LoggingPrefs.romm }

    fun init(piannoliRoot: String) = log.init(piannoliRoot)

    fun write(message: String) = log.write(message)
}
