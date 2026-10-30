package dev.piannoli.scorza.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.piannoli.scorza.R
import dev.piannoli.scorza.settings.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

enum class MenuSound { TICK, CONFIRM, BACK }

@ActivityScoped
class MenuSounds internal constructor(
    context: Context,
    private val settings: SettingsRepository,
    private val pool: SoundPool,
) {
    @Inject constructor(@ActivityContext context: Context, settings: SettingsRepository) : this(
        context,
        settings,
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    )

    private val ready = ConcurrentHashMap.newKeySet<Int>()
    private val sounds: Map<MenuSound, Int>
    private var released = false

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) ready.add(id) else ErrorLog.write("Menu sound $id could not load (status $status)")
        }
        sounds = mapOf(
            MenuSound.TICK to pool.load(context, R.raw.menu_tick, 1),
            MenuSound.CONFIRM to pool.load(context, R.raw.menu_confirm, 1),
            MenuSound.BACK to pool.load(context, R.raw.menu_back, 1),
        )
    }

    fun play(sound: MenuSound) {
        if (released || !settings.navigationSound) return
        val id = sounds.getValue(sound)
        if (id !in ready) return
        if (pool.play(id, 1f, 1f, 1, 0, 1f) == 0) ErrorLog.write("Menu sound $sound could not play")
    }

    fun release() {
        released = true
        pool.setOnLoadCompleteListener(null)
        ready.clear()
        pool.release()
    }
}
