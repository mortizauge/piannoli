package dev.cannoli.scorza.util

import android.content.Context
import android.media.SoundPool
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.R
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuSoundsTest {
    @Test fun `menu audio plays with system touch sounds disabled and stops after release`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.System.putInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0)
        val pool = mockk<SoundPool>(relaxed = true)
        val listener = slot<SoundPool.OnLoadCompleteListener>()
        every { pool.setOnLoadCompleteListener(capture(listener)) } returns Unit
        every { pool.load(context, R.raw.menu_tick, 1) } returns 1
        every { pool.load(context, R.raw.menu_confirm, 1) } returns 2
        every { pool.load(context, R.raw.menu_back, 1) } returns 3
        every { pool.play(any(), any(), any(), any(), any(), any()) } returns 1
        val settings = mockk<SettingsRepository>()
        every { settings.navigationSound } returns true
        val sounds = MenuSounds(context, settings, pool)
        sounds.play(MenuSound.TICK)
        verify(exactly = 0) { pool.play(any(), any(), any(), any(), any(), any()) }
        for (id in 1..3) listener.captured.onLoadComplete(pool, id, 0)
        every { settings.navigationSound } returns false
        for (sound in MenuSound.entries) sounds.play(sound)
        verify(exactly = 0) { pool.play(any(), any(), any(), any(), any(), any()) }
        every { settings.navigationSound } returns true
        sounds.play(MenuSound.TICK)
        sounds.play(MenuSound.CONFIRM)
        sounds.play(MenuSound.BACK)
        sounds.release()
        sounds.play(MenuSound.TICK)
        for (id in 1..3) verify(exactly = 1) { pool.play(id, 1f, 1f, 1, 0, 1f) }
        verify(exactly = 1) { pool.release() }
    }
}
