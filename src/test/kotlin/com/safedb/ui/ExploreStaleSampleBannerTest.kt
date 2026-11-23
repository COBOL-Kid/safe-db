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
        assertEquals(
            "The active connection changed. Reopen Explore from the query you " +
                "want to inspect; Refresh cannot update this sample.",
            connectionChanged.text,
        )
        assertFalse(connectionChanged.showRefreshAction)

        val newerWithRefresh =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.NewerSample,
                origin = ExploreOrigin.Builder,
                refreshAvailable = true,
            )
        assertEquals(
            "Newer Builder results are available. Refresh the Explore sample to load them.",
            newerWithRefresh.text,
        )
        assertTrue(newerWithRefresh.showRefreshAction)

        val newerWithoutRefresh =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.NewerSample,
                origin = ExploreOrigin.Builder,
                refreshAvailable = false,
            )
        assertEquals(
            "Newer Builder results are available. Reopen Explore from Builder to inspect them.",
            newerWithoutRefresh.text,
        )
        assertFalse(newerWithoutRefresh.showRefreshAction)

        val specChangedWithRefresh =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.SpecChanged,
                origin = ExploreOrigin.Sql,
                refreshAvailable = true,
            )
        assertEquals(
            "The SQL query changed. Refresh the Explore sample from the latest SQL results.",
            specChangedWithRefresh.text,
        )
        assertTrue(specChangedWithRefresh.showRefreshAction)

        val specChangedWithoutRefresh =
            exploreStaleSampleBanner(
                staleness = SampleStaleness.SpecChanged,
                origin = ExploreOrigin.Sql,
                refreshAvailable = false,
            )
        assertEquals(
            "The SQL query changed. Re-run the query in SQL, then refresh or reopen Explore.",
            specChangedWithoutRefresh.text,
        )
        assertFalse(specChangedWithoutRefresh.showRefreshAction)
    }
}
