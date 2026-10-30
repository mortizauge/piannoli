package dev.piannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.input.InputTesterController
import dev.piannoli.scorza.input.ScreenInputHandler
import dev.piannoli.scorza.navigation.NavigationController
import javax.inject.Inject

@ActivityScoped
class InputTesterInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val controller: InputTesterController,
) : ScreenInputHandler {
    override fun onBack() = nav.pop()
}
