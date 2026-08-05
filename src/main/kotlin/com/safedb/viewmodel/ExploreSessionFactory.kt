package com.safedb.viewmodel

import com.safedb.explore.ExploreSession
import com.safedb.explore.exploreSpecHash
import com.safedb.model.ConnectionDef
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import java.time.Instant

fun createExploreSession(
    connection: ConnectionDef,
    spec: QuerySpec,
    sample: QueryResult,
): ExploreSession =
    ExploreSession(
        connectionId = connection.id,
        connectionLabel = connection.name,
        baseSpec = spec,
        baseSpecHash = exploreSpecHash(spec),
        sample = sample,
        sampleFetchedAtEpochSec = Instant.now().epochSecond,
        builderLimit = spec.limit,
    )
