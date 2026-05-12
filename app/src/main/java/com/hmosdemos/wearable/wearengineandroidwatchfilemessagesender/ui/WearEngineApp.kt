package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ui.screens.ForegroundServiceScreen
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ui.screens.MessageFileSenderScreen
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.viewmodels.MainViewModel

private enum class BottomTab(val label: String) {
    WearEngine("Wear Engine"),
    Service("Service"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WearEngineApp(
    viewModel: MainViewModel,
) {
    var selectedTab by remember { mutableStateOf(BottomTab.WearEngine) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when (selectedTab) {
                                BottomTab.WearEngine -> "Wear Engine Message & File Sender"
                                BottomTab.Service -> "Foreground Service"
                            },
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        )
                        if (selectedTab == BottomTab.WearEngine) {
                            IconButton(onClick = {
                                viewModel.clearLogs()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear Logs",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == BottomTab.WearEngine,
                    onClick = { selectedTab = BottomTab.WearEngine },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = BottomTab.WearEngine.label
                        )
                    },
                    label = { Text(BottomTab.WearEngine.label) }
                )
                NavigationBarItem(
                    selected = selectedTab == BottomTab.Service,
                    onClick = { selectedTab = BottomTab.Service },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = BottomTab.Service.label
                        )
                    },
                    label = { Text(BottomTab.Service.label) }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { contentPadding ->
        when (selectedTab) {
            BottomTab.WearEngine -> MessageFileSenderScreen(
                viewModel = viewModel,
                contentPadding = contentPadding
            )
            BottomTab.Service -> ForegroundServiceScreen(
                contentPadding = contentPadding
            )
        }
    }
}
