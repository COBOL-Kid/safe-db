package com.safedb.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException

class SeedTaskArgsTest {
    @Test
    fun blankPropertyProducesNoArguments() {
        assertEquals(emptyList(), splitSeedTaskArgs("", "seedMysqlArgs"))
        assertEquals(emptyList(), splitSeedTaskArgs("   \t ", "seedMysqlArgs"))
    }

    @Test
    fun splitsOnUnquotedWhitespaceAndKeepsQuotedGroupsIntact() {
        assertEquals(
            listOf("--orders", "20000", "--label", "north west", "--tag", "a b"),
            splitSeedTaskArgs(
                """--orders 20000 --label "north west" --tag 'a b'""",
                "seedPostgresArgs",
            ),
        )
    }

    @Test
    fun unescapesBackslashesAndEmbeddedQuotes() {
        // What scripts/seed_relational.sh emits for the single argument: --label say "hi"\x
        assertEquals(
            listOf("""--label say "hi"\x"""),
            splitSeedTaskArgs("""  "--label say \"hi\"\\x"  """, "seedOracleArgs"),
        )
    }

    @Test
    fun trailingBackslashSurvivesAsALiteral() {
        assertEquals(listOf("""C:\"""), splitSeedTaskArgs("""C:\""", "seedMssqlArgs"))
    }

    @Test
    fun unterminatedQuoteFailsAndNamesTheProperty() {
        val failure =
            assertFailsWith<GradleException> {
                splitSeedTaskArgs("""--label "north west""", "seedPostgresArgs")
            }
        assertTrue(failure.message!!.contains("seedPostgresArgs"), failure.message)
    }
}
