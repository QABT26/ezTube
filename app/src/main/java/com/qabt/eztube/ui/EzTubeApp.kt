package com.qabt.eztube.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class Tab(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library") }

@Composable
fun EzTubeApp() {
    var selected by remember { mutableStateOf(Tab.HOME) }

    MaterialTheme {
        Scaffold(
            topBar = {
                Surface(shadowElevation = 1.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ezTube", style = MaterialTheme.typography.titleLarge)
                        Text("Audio first", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selected = tab },
                            icon = {
                                Icon(
                                    when (tab) {
                                        Tab.HOME -> Icons.Outlined.Home
                                        Tab.SEARCH -> Icons.Outlined.Search
                                        Tab.LIBRARY -> Icons.Outlined.LibraryMusic
                                    },
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(selected.label, style = MaterialTheme.typography.headlineMedium)
                Text(
                    when (selected) {
                        Tab.HOME -> "Audio-first feed foundation."
                        Tab.SEARCH -> "Search foundation. YouTube source integration comes next."
                        Tab.LIBRARY -> "History, favorites and playlists will live here."
                    }
                )
            }
        }
    }
}
