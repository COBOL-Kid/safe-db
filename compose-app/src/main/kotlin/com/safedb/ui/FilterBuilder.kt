package com.safedb.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.safedb.viewmodel.QueryViewModel

@Composable
fun FilterBuilder(
    queryViewModel: QueryViewModel,
    modifier: Modifier = Modifier,
) {
    FilterGroupCard(
        queryViewModel = queryViewModel,
        group = queryViewModel.filters,
        path = emptyList(),
        depth = 0,
        modifier = modifier,
    )
}
