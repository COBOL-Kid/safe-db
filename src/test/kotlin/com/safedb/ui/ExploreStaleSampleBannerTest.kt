package com.safedb.ui

import com.safedb.viewmodel.ExploreOrigin
import com.safedb.viewmodel.SampleStaleness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreStaleSampleBannerTest {
    @Test
    fun bannerCopyAndRefreshActionFollowTheRefreshCallback() {
        val fresh =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.Fresh,
                origin = ExploreOrigin.Builder,
                refreshAvailable = true,
            )
        assertEquals("", fresh.text)
        assertFalse(fresh.showRefreshAction)

        val connectionChanged =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.ConnectionChanged,
                origin = ExploreOrigin.Sql,
                refreshAvailable = true,
            )
        assertTrue(connectionChanged.text.isNotBlank())
        assertTrue(connectionChanged.text.contains("connection", ignoreCase = true))
        assertFalse(connectionChanged.showRefreshAction)

        val newerWithRefresh =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.NewerSample,
                origin = ExploreOrigin.Builder,
                refreshAvailable = true,
            )
        assertTrue(newerWithRefresh.text.isNotBlank())
        assertTrue(newerWithRefresh.text.contains("Refresh", ignoreCase = true))
        assertTrue(newerWithRefresh.showRefreshAction)

        val newerWithoutRefresh =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.NewerSample,
                origin = ExploreOrigin.Builder,
                refreshAvailable = false,
            )
        assertTrue(newerWithoutRefresh.text.isNotBlank())
        assertTrue(newerWithoutRefresh.text.contains("Reopen", ignoreCase = true))
        assertFalse(newerWithoutRefresh.showRefreshAction)

        val specChangedWithRefresh =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.SpecChanged,
                origin = ExploreOrigin.Sql,
                refreshAvailable = true,
            )
        assertTrue(specChangedWithRefresh.text.isNotBlank())
        assertTrue(specChangedWithRefresh.text.contains("SQL", ignoreCase = true))
        assertTrue(specChangedWithRefresh.showRefreshAction)

        val specChangedWithoutRefresh =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.SpecChanged,
                origin = ExploreOrigin.Sql,
                refreshAvailable = false,
            )
        assertTrue(specChangedWithoutRefresh.text.isNotBlank())
        assertTrue(specChangedWithoutRefresh.text.contains("SQL", ignoreCase = true))
        assertFalse(specChangedWithoutRefresh.showRefreshAction)
    }
}
