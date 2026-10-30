package dev.piannoli.scorza.di

import android.app.Activity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.R
import dev.piannoli.scorza.input.InputTesterController
import dev.piannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.piannoli.ui.components.OsdController

@Module
@InstallIn(ActivityComponent::class)
object InputModule {

    @Provides @ActivityScoped
    fun provideOsdController(): OsdController = OsdController()

    @Provides @ActivityScoped
    fun provideInputTesterController(
        activity: Activity,
        viewModel: InputTesterViewModel,
        portRouter: dev.piannoli.scorza.input.runtime.PortRouter,
        activeMappingHolder: dev.piannoli.scorza.input.runtime.ActiveMappingHolder,
    ): InputTesterController = InputTesterController(
        viewModel = viewModel,
        portRouter = portRouter,
        activeMappingHolder = activeMappingHolder,
        unknownDeviceName = activity.getString(R.string.input_tester_device_unknown),
        keyboardDeviceName = activity.getString(R.string.input_tester_device_keyboard),
    )
}
