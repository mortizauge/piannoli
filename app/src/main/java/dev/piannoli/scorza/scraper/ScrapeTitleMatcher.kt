package dev.piannoli.scorza.scraper

import dev.piannoli.scorza.util.TextNormalizer

internal object ScrapeTitleMatcher {
    fun cleanedQuery(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun normalized(title: String) = TextNormalizer.normalize(title).filter { it.isLetterOrDigit() }

    fun exact(title: String, matches: List<SteamGridGame>): SteamGridGame? {
        val name = normalized(title)
        return matches.distinctBy { it.id }.filter { name.isNotEmpty() && normalized(it.name) == name }.singleOrNull()
    }

    fun closest(title: String, matches: List<SteamGridGame>): SteamGridGame? {
        if (normalized(title).isEmpty()) return null
        return matches.distinctBy { it.id }.filter { normalized(it.name).isNotEmpty() }
            .sortedWith(compareByDescending<SteamGridGame> { similarity(title, it.name) }.thenBy { it.id })
            .firstOrNull()
    }

    private fun tokens(title: String): Set<String> =
        cleanedQuery(TextNormalizer.normalize(title)).split(' ').filter { it.isNotEmpty() }
            .map { romanNumbers[it] ?: it }.toSet()

    private val romanNumbers = mapOf(
        "i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10",
    )

    private fun similarity(title: String, candidate: String): Double {
        val leftTokens = tokens(title)
        val rightTokens = tokens(candidate)
        val left = leftTokens.joinToString(" ")
        val right = rightTokens.joinToString(" ")
        val characterScore = 1.0 - editDistance(left, right).toDouble() / maxOf(left.length, right.length, 1)
        val tokenScore = 2.0 * leftTokens.intersect(rightTokens).size / maxOf(leftTokens.size + rightTokens.size, 1)
        // Avoid favoring a different sequel merely because its title is otherwise identical.
        val leftNumbers = leftTokens.filter { token -> token.all { it.isDigit() } }.toSet()
        val rightNumbers = rightTokens.filter { token -> token.all { it.isDigit() } }.toSet()
        val sequelPenalty = if (leftNumbers == rightNumbers) 0.0 else 0.4
        return characterScore * 0.6 + tokenScore * 0.4 - sequelPenalty
    }

    private fun editDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                current[j + 1] = minOf(
                    current[j] + 1, previous[j + 1] + 1,
                    previous[j] + if (left[i] == right[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }
}
