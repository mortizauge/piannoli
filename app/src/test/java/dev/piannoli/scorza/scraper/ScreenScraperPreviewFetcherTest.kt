package dev.piannoli.scorza.scraper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import dev.piannoli.scorza.settings.ScreenScraperMedia
import dev.piannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenScraperPreviewFetcherTest {
    private val provider = mockk<ScraperProvider> {
        every { configured } returns true
        every { requestDelayMillis(any()) } returns 0L
    }
    private val settings = mockk<SettingsRepository> {
        every { screenScraperUsername } returns "saved-user"
        every { screenScraperPassword } returns "saved-password"
        every { screenScraperMedia } returns ScreenScraperMedia.COVER
    }

    @Test fun `preview models carry only artwork identifiers and fetch using the saved account`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        every { provider.preview(any(), any()) } returns bytes
        val match = ScraperMatch(10, "Game", ScraperSystem(4, "SNES"), ScraperArtwork("wheel", "us"))
        val uri = Uri.parse(ScreenScraperPreviewFetcher.uri(match))
        assertEquals(setOf("region"), uri.queryParameterNames)
        assertEquals(listOf("4", "10", "wheel"), uri.pathSegments)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fetcher = ScreenScraperPreviewFetcher.Factory(provider, settings).create(uri, Options(context), mockk())
        val result = fetcher!!.fetch() as coil.fetch.SourceResult
        result.source.use { assertArrayEquals(bytes, it.source().readByteArray()) }
        verify(exactly = 1) {
            provider.preview(ScraperAccount("saved-user", "saved-password"),
                match { it.id == 10 && it.system.id == 4 && it.artwork == ScraperArtwork("wheel", "us") })
        }
        verify(exactly = 0) { provider.download(any(), any()) }
        assertNull(ScreenScraperPreviewFetcher.uri(match.copy(artwork = null)))
    }

    @Test fun `image loader resolves preview strings while ordinary URLs remain with the default fetchers`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = ScreenScraperPreviewFetcher.Factory(provider, settings)
        val loader = ImageLoader.Builder(context).components { add(factory) }.build()
        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            bitmap.recycle()
            every { provider.preview(any(), any()) } returns bytes
            val match = ScraperMatch(10, "Game", ScraperSystem(4, "SNES"), ScraperArtwork("box-2D", "eu"))
            val result = loader.execute(ImageRequest.Builder(context)
                .data(ScreenScraperPreviewFetcher.uri(match)).size(32).allowHardware(false).build())
            assertTrue(result.toString(), result is SuccessResult)
            verify(exactly = 1) { provider.preview(any(), any()) }
            assertNull(factory.create(Uri.parse("https://example.com/art.png"), Options(context), loader))
        } finally {
            loader.shutdown()
            Dispatchers.resetMain()
        }
    }
}
