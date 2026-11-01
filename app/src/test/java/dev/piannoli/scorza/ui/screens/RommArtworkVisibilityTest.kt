package dev.piannoli.scorza.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil.ImageLoader
import coil.EventListener
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.ImageRequest
import dev.piannoli.scorza.romm.LocalState
import dev.piannoli.scorza.romm.RommArtType
import dev.piannoli.scorza.romm.RommGame
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp")
class RommArtworkVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun `hidden RomM detail skips requests and hiding cancels existing cover request`() {
        val show = mutableStateOf(false)
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val loader = ImageLoader.Builder(compose.activity).eventListener(object : EventListener {
            override fun onStart(request: ImageRequest) { started.incrementAndGet() }
            override fun onCancel(request: ImageRequest) { cancelled.incrementAndGet() }
        }).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    awaitCancellation()
                }
            })
        }.build()
        val game = RommGame(
            id = 1, platformId = 1, name = "Game", fsName = "game.zip", sizeBytes = 0,
            summary = null, revision = null, regions = emptyList(), languages = emptyList(),
            coverPath = "/cover.png", files = emptyList(),
        )
        try {
            compose.setContent {
                RommGameDetailScreen(
                    game = game, platformName = "System", localState = LocalState.PRESENT,
                    host = "https://romm.invalid", artType = RommArtType.BOX2D, imageLoader = loader,
                    scrollStep = 0, onScrollStepChanged = {}, showArtwork = show.value,
                )
            }
            compose.waitForIdle()
            ShadowLooper.idleMainLooper()
            compose.runOnIdle { assertEquals(0, started.get()); show.value = true }
            compose.waitUntil(5_000) { ShadowLooper.idleMainLooper(); started.get() == 1 }
            compose.runOnIdle { show.value = false }
            compose.waitUntil(5_000) { ShadowLooper.idleMainLooper(); cancelled.get() == 1 }
            compose.runOnIdle { assertEquals(1, started.get()); show.value = true }
            compose.waitUntil(5_000) { ShadowLooper.idleMainLooper(); started.get() == 2 }
        } finally {
            loader.shutdown()
        }
    }
}
