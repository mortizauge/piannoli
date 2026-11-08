package dev.piannoli.scorza.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.piannoli.scorza.di.PiannoliPathsProvider
import dev.piannoli.scorza.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var db: PiannoliDatabase
    private lateinit var repo: CollectionsRepository
    private var collection = 0L

    @Before fun setup() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = temp.root.absolutePath
        db = PiannoliDatabase(PiannoliPathsProvider(settings))
        repo = CollectionsRepository(db)
        collection = repo.create("Games")
        db.execute("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES ('GB', 'Game Boy')")
    }

    private fun rom(name: String): LibraryRef = LibraryRef.Rom(db.executeReturningId(
        "INSERT INTO roms (path, platform_tag, display_name) VALUES (?, 'GB', ?)", "$name.gb", name,
    ))

    private fun app(name: String): LibraryRef = LibraryRef.App(db.executeReturningId(
        "INSERT INTO apps (type, display_name, package_name) VALUES ('PORT', ?, ?)", name, "dev.$name",
    ))

    @Test fun `new ROMs and apps insert alphabetically across batches without duplicating members`() {
        val zulu = rom("Zulu")
        val alpha = app("alpha")
        val mango = rom("Mango")
        val beta = app("beta")
        for (ref in listOf(zulu, alpha, mango, beta, alpha)) repo.addMember(collection, ref)
        assertEquals(listOf(alpha, beta, mango, zulu), repo.membersIn(collection))
        assertEquals(listOf(mango.id, zulu.id), repo.romIdsIn(collection))
        assertEquals(listOf(alpha.id, beta.id), repo.appIdsIn(collection))
    }

    @Test fun `insertion preserves existing manual order and other collections`() {
        val zulu = rom("Zulu")
        val alpha = app("Alpha")
        repo.addMember(collection, alpha)
        repo.addMember(collection, zulu)
        repo.setMemberOrder(collection, listOf(zulu, alpha))
        val other = repo.create("Other")
        repo.addMember(other, alpha)
        val mango = rom("Mango")
        repo.addMember(collection, mango)
        assertEquals(listOf(mango, zulu, alpha), repo.membersIn(collection))
        assertEquals(listOf(alpha), repo.membersIn(other))
        db.execute("UPDATE roms SET is_hidden = 1 WHERE id = ?", zulu.id)
        assertEquals(listOf(mango, alpha), repo.membersIn(collection))
    }

    @Test fun `last added follows collection membership insertion including removal and readdition`() {
        val alpha = rom("Alpha")
        val zulu = app("Zulu")
        repo.addMember(collection, zulu)
        repo.addMember(collection, alpha)
        repo.addMember(collection, zulu)
        repo.setMemberOrder(collection, listOf(zulu, alpha))
        assertEquals(listOf(alpha, zulu), repo.membersByLastAdded(collection))
        repo.removeMember(collection, zulu)
        repo.addMember(collection, zulu)
        assertEquals(listOf(zulu, alpha), repo.membersByLastAdded(collection))
        val other = repo.create("Other")
        repo.addMember(other, alpha)
        assertEquals(listOf(alpha), repo.membersByLastAdded(other))
        db.execute("UPDATE apps SET is_hidden = 1 WHERE id = ?", zulu.id)
        assertEquals(listOf(alpha), repo.membersByLastAdded(collection))
    }
}
