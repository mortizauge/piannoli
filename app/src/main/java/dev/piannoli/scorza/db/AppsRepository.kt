package dev.piannoli.scorza.db

import androidx.sqlite.SQLiteStatement
import dev.piannoli.scorza.model.App
import dev.piannoli.scorza.model.AppType
import dev.piannoli.scorza.model.artTag
import dev.piannoli.scorza.romm.download.sanitizeFsName
import dev.piannoli.scorza.util.ArtworkLookup

class AppsRepository(
    private val db: PiannoliDatabase,
    private val artwork: ArtworkLookup,
) {
    fun all(type: AppType? = null): List<App> = if (type != null) {
        db.queryAll(
            "SELECT $COLUMNS FROM apps WHERE type = ? ORDER BY $ORDER",
            type.name,
            mapper = ::rowToApp,
        )
    } else {
        db.queryAll(
            "SELECT $COLUMNS FROM apps ORDER BY type, $ORDER",
            mapper = ::rowToApp,
        )
    }

    fun byId(appId: Long): App? = db.queryOne(
        "SELECT $COLUMNS FROM apps WHERE id = ?", appId, mapper = ::rowToApp,
    )

    fun byPackage(type: AppType, packageName: String): App? = db.queryOne(
        "SELECT $COLUMNS FROM apps WHERE type = ? AND package_name = ?",
        type.name, packageName, mapper = ::rowToApp,
    )

    fun count(type: AppType): Int = db.queryOne(
        "SELECT COUNT(*) FROM apps WHERE type = ?", type.name,
    ) { it.getInt(0) } ?: 0

    fun upsert(type: AppType, displayName: String, packageName: String): Long {
        db.execute(
            """
            INSERT INTO apps (type, display_name, package_name) VALUES (?, ?, ?)
            ON CONFLICT(type, package_name) DO NOTHING
            """.trimIndent(),
            type.name, displayName, packageName,
        )
        return byPackage(type, packageName)!!.id
    }

    fun updateDisplayName(appId: Long, displayName: String) = db.execute(
        "UPDATE apps SET display_name = ? WHERE id = ?", displayName, appId,
    )

    fun delete(appId: Long) = db.execute("DELETE FROM apps WHERE id = ?", appId)

    fun setToolsPinned(appIds: List<Long>, pinned: Boolean): Boolean = db.transaction { conn ->
        val tools = conn.queryAll("SELECT id, pin_order FROM apps WHERE type = 'TOOL'") {
            it.getLong(0) to if (it.isNull(1)) null else it.getLong(1)
        }.toMap()
        val ids = appIds.distinct()
        require(ids.all { it in tools }) { "Only tools can be pinned" }
        val additions = ids.filter { tools[it] == null }
        if (pinned && tools.values.count { it != null } + additions.size > 5) return@transaction false
        var next = (tools.values.filterNotNull().maxOrNull() ?: 0L) + 1
        for (id in ids) {
            if (!pinned) conn.execute("UPDATE apps SET pin_order = NULL WHERE id = ?", id)
            else if (tools[id] == null) conn.execute("UPDATE apps SET pin_order = ? WHERE id = ?", next++, id)
        }
        true
    }

    fun setOrder(orderedIds: List<Long>) = db.transaction { conn ->
        orderedIds.forEachIndexed { index, id ->
            conn.execute(
                "UPDATE apps SET sort_order = ?, pin_order = CASE WHEN pin_order IS NULL THEN NULL ELSE ? END WHERE id = ?",
                index.toLong(), -index.toLong(), id)
        }
    }

    private fun rowToApp(stmt: SQLiteStatement): App {
        val type = AppType.valueOf(stmt.getText(1))
        val displayName = stmt.getText(2)
        return App(
            id = stmt.getLong(0),
            type = type,
            displayName = displayName,
            packageName = stmt.getText(3),
            lastPlayedAt = if (stmt.isNull(4)) null else stmt.getLong(4),
            artFile = artwork.findByName(type.artTag, sanitizeFsName(displayName)),
            isPinned = !stmt.isNull(5),
        )
    }

    private companion object {
        const val COLUMNS = "id, type, display_name, package_name, last_played_at, pin_order"
        const val ORDER = "(pin_order IS NULL), pin_order DESC, sort_order, display_name COLLATE NOCASE"
    }
}
