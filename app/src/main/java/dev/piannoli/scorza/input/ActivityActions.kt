package dev.piannoli.scorza.input

interface ActivityActions {
    fun finishAffinity()
    fun restartApp()
    fun startRaLogin(username: String, password: String)
    fun startRommPairing(host: String)
    fun startRommCodePairing(host: String, pairCode: String)
    fun chooseSystemArtwork(tag: String, type: dev.piannoli.scorza.settings.SystemArtworkType)
}
