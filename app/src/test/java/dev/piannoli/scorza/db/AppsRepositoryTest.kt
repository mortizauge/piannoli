package dev.piannoli.scorza.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.settings.SettingsRepository
import dev.piannoli.scorza.util.ArtworkLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var repo: AppsRepository
    private lateinit var database: PiannoliDatabase
    private lateinit var paths: PiannoliPathsProvider

    @Before fun setUp() {
        root = tmp.newFolder("piannoli")
        File(root, "Config").mkdirs()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        paths = PiannoliPathsProvider(settings)
        database = PiannoliDatabase(paths)
        repo = AppsRepository(database, ArtworkLookup(paths))
    }

    private fun writeArt(tag: String, fileName: String): File {
        val dir = File(root, "Art/$tag").apply { mkdirs() }
        return File(dir, fileName).apply { writeBytes(ByteArray(4)) }
    }

    @Test fun `tool art resolves from the TOOLS directory`() {
        val art = writeArt("TOOLS", "Termux.png")
        val id = repo.upsert(AppType.TOOL, "Termux", "com.termux")
        assertEquals(art, repo.byId(id)?.artFile)
    }

    @Test fun `port art resolves from the PORTS directory`() {
        val art = writeArt("PORTS", "Portal 2.jpg")
        val id = repo.upsert(AppType.PORT, "Portal 2", "com.valve.portal2")
        assertEquals(art, repo.byId(id)?.artFile)
    }

    @Test fun `artFile is null when no art exists`() {
        val id = repo.upsert(AppType.TOOL, "Termux", "com.termux")
        assertNull(repo.byId(id)?.artFile)
    }

    @Test fun `a tool does not pick up art from the ports directory`() {
        writeArt("PORTS", "Termux.png")
        val id = repo.upsert(AppType.TOOL, "Termux", "com.termux")
        assertNull(repo.byId(id)?.artFile)
    }

    @Test fun `a colon in the display name resolves the sanitized art file`() {
        val art = writeArt("TOOLS", "Moonlight - Game Streaming.png")
        val id = repo.upsert(AppType.TOOL, "Moonlight: Game Streaming", "com.limelight")
        assertEquals(art, repo.byId(id)?.artFile)
    }

    @Test fun `a slash in the display name resolves the sanitized art file`() {
        val art = writeArt("PORTS", "Doom_Doom II.png")
        val id = repo.upsert(AppType.PORT, "Doom/Doom II", "org.gzdoom")
        assertEquals(art, repo.byId(id)?.artFile)
    }

    @Test fun `all resolves art for every row`() {
        val art = writeArt("TOOLS", "Termux.png")
        repo.upsert(AppType.TOOL, "Termux", "com.termux")
        repo.upsert(AppType.TOOL, "No Art", "com.example.noart")
        val byName = repo.all(AppType.TOOL).associateBy { it.displayName }
        assertEquals(art, byName["Termux"]?.artFile)
        assertNull(byName["No Art"]?.artFile)
    }

    @Test fun `pins persist at top independently of Favorites and unpin restores normal order`() {
        val collections = CollectionsRepository(database)
        val favorites = collections.create("Favorites", dev.piannoli.scorza.model.CollectionType.FAVORITES)
        val alpha = repo.upsert(AppType.TOOL, "Alpha", "dev.alpha")
        val beta = repo.upsert(AppType.TOOL, "Beta", "dev.beta")
        val zulu = repo.upsert(AppType.TOOL, "Zulu", "dev.zulu")
        collections.addMember(favorites, LibraryRef.App(alpha))
        assertTrue(repo.setToolsPinned(listOf(beta), true))
        assertTrue(repo.setToolsPinned(listOf(zulu), true))
        assertEquals(listOf(zulu, beta, alpha), repo.all(AppType.TOOL).map { it.id })
        assertEquals(setOf(alpha), collections.favoriteAppIds())
        database.close()
        database = PiannoliDatabase(paths)
        repo = AppsRepository(database, ArtworkLookup(paths))
        assertTrue(repo.byId(zulu)!!.isPinned)
        assertEquals(listOf(zulu, beta, alpha), repo.all(AppType.TOOL).map { it.id })
        assertTrue(repo.setToolsPinned(listOf(zulu), false))
        assertEquals(listOf(beta, alpha, zulu), repo.all(AppType.TOOL).map { it.id })
        assertFalse(repo.byId(zulu)!!.isPinned)
    }

    @Test fun `five pin cap is atomic for bulk requests and unpinning frees a slot`() {
        val ids = (1..7).map { repo.upsert(AppType.TOOL, "Tool $it", "dev.tool$it") }
        assertTrue(repo.setToolsPinned(ids.take(4), true))
        assertFalse(repo.setToolsPinned(ids.takeLast(2), true))
        assertEquals(4, repo.all(AppType.TOOL).count { it.isPinned })
        assertTrue(repo.setToolsPinned(listOf(ids[4], ids[4]), true))
        assertTrue(repo.setToolsPinned(listOf(ids[4]), true))
        assertFalse(repo.setToolsPinned(listOf(ids[5]), true))
        assertTrue(repo.setToolsPinned(listOf(ids[0]), false))
        assertTrue(repo.setToolsPinned(listOf(ids[5]), true))
        assertEquals(5, repo.all(AppType.TOOL).count { it.isPinned })
        val port = repo.upsert(AppType.PORT, "Port", "dev.port")
        assertThrows(IllegalArgumentException::class.java) { repo.setToolsPinned(listOf(port), true) }
        assertFalse(repo.byId(port)!!.isPinned)
    }

    @Test fun `manual ordering keeps pins together and deleting a pinned tool frees its slot`() {
        val ids = (1..6).map { repo.upsert(AppType.TOOL, "Tool $it", "dev.tool$it") }
        assertTrue(repo.setToolsPinned(ids.take(5), true))
        repo.setOrder(ids)
        assertEquals(ids, repo.all(AppType.TOOL).map { it.id })
        repo.delete(ids.first())
        assertTrue(repo.setToolsPinned(listOf(ids.last()), true))
        assertEquals(ids.last(), repo.all(AppType.TOOL).first().id)
    }
}
