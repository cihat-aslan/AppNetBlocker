package com.appnetblocker.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.appnetblocker.R
import com.appnetblocker.data.AppInfo
import com.appnetblocker.service.VpnError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    snackbarHostState: SnackbarHostState,
    loadAppIcon: suspend (String) -> Drawable?,
    onToggleVpn: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onToggleApp: (String) -> Unit,
    onQuickPickOpen: () -> Unit,
    onQuickPickDismiss: () -> Unit,
    onQuickPickSubmit: (String) -> Unit,
    onConfirmVpnExplanation: () -> Unit,
    onDismissVpnExplanation: () -> Unit,
    onDismissVpnError: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_cd)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "status_toggle_card") {
                VpnStatusCard(
                    isVpnRunning = uiState.isVpnRunning,
                    blockedCount = uiState.blockedCount,
                    selectedCount = uiState.selectedPackages.size,
                    onToggle = onToggleVpn
                )
            }

            if (uiState.isAnotherVpnActive) {
                item(key = "warning_other_vpn") {
                    WarningBannerCard(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.warning_another_vpn_title),
                        body = stringResource(R.string.warning_another_vpn_body),
                        isErrorStyle = true
                    )
                }
            }

            if (uiState.wasServiceKilledUnexpectedly) {
                item(key = "warning_service_killed") {
                    WarningBannerCard(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.warning_service_killed_title),
                        body = stringResource(R.string.warning_service_killed_body),
                        isErrorStyle = true,
                        primaryActionLabel = stringResource(R.string.action_open_settings),
                        onPrimaryAction = onNavigateToSettings,
                        dismissActionLabel = stringResource(R.string.action_dismiss),
                        onDismissAction = onDismissVpnError
                    )
                }
            } else if (uiState.vpnError != null) {
                item(key = "warning_vpn_error") {
                    VpnErrorBanner(
                        error = uiState.vpnError,
                        onOpenSettings = onNavigateToSettings,
                        onDismiss = onDismissVpnError
                    )
                }
            }

            if (uiState.showWorkProfileNote) {
                item(key = "warning_work_profile") {
                    WarningBannerCard(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.warning_work_profile_title),
                        body = stringResource(R.string.warning_work_profile_body),
                        isErrorStyle = false
                    )
                }
            }

            item(key = "search_and_filters") {
                SearchAndFilterControls(
                    searchQuery = uiState.searchQuery,
                    showSystemApps = uiState.showSystemApps,
                    onSearchQueryChange = onSearchQueryChange,
                    onShowSystemAppsChange = onShowSystemAppsChange,
                    onQuickPickOpen = onQuickPickOpen
                )
            }

            when {
                uiState.isLoadingApps -> {
                    item(key = "loading_apps") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.loading_apps),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                uiState.visibleSelectedApps.isEmpty() && uiState.visibleUnselectedApps.isEmpty() -> {
                    item(key = "empty_apps") {
                        Text(
                            text = stringResource(R.string.empty_apps_search),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    if (uiState.visibleSelectedApps.isNotEmpty()) {
                        item(key = "header_selected") {
                            SectionHeader(
                                text = stringResource(
                                    R.string.section_selected_apps,
                                    uiState.visibleSelectedApps.size
                                )
                            )
                        }
                        items(
                            items = uiState.visibleSelectedApps,
                            key = { "selected_${it.packageName}" }
                        ) { app ->
                            AppListRow(
                                app = app,
                                isSelected = true,
                                loadAppIcon = loadAppIcon,
                                onToggle = { onToggleApp(app.packageName) }
                            )
                        }
                    }

                    if (uiState.visibleUnselectedApps.isNotEmpty()) {
                        item(key = "header_unselected") {
                            SectionHeader(
                                text = stringResource(
                                    R.string.section_other_apps,
                                    uiState.visibleUnselectedApps.size
                                )
                            )
                        }
                        items(
                            items = uiState.visibleUnselectedApps,
                            key = { "unselected_${it.packageName}" }
                        ) { app ->
                            AppListRow(
                                app = app,
                                isSelected = false,
                                loadAppIcon = loadAppIcon,
                                onToggle = { onToggleApp(app.packageName) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (uiState.showVpnExplanationDialog) {
        AlertDialog(
            onDismissRequest = onDismissVpnExplanation,
            title = { Text(text = stringResource(R.string.vpn_explain_title)) },
            text = { Text(text = stringResource(R.string.vpn_explain_body)) },
            confirmButton = {
                Button(onClick = onConfirmVpnExplanation) {
                    Text(text = stringResource(R.string.vpn_explain_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissVpnExplanation) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (uiState.showQuickPickDialog) {
        QuickPickDialog(
            allInstalledApps = uiState.allInstalledApps,
            errorPackage = uiState.quickPickErrorPackage,
            onDismiss = onQuickPickDismiss,
            onSubmit = onQuickPickSubmit
        )
    }
}

@Composable
private fun VpnStatusCard(
    isVpnRunning: Boolean,
    blockedCount: Int,
    selectedCount: Int,
    onToggle: (Boolean) -> Unit
) {
    val containerColor = if (isVpnRunning) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isVpnRunning) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val titleText = if (isVpnRunning) {
        pluralStringResource(R.plurals.status_blocking_apps, blockedCount, blockedCount)
    } else {
        stringResource(R.string.status_off)
    }

    val subtitleText = when {
        isVpnRunning -> stringResource(R.string.status_subtitle_on)
        selectedCount == 0 -> stringResource(R.string.status_subtitle_off_none)
        else -> pluralStringResource(R.plurals.status_subtitle_off_ready, selectedCount, selectedCount)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isVpnRunning) },
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_vpn_shield),
                contentDescription = stringResource(R.string.toggle_vpn_cd),
                modifier = Modifier.size(40.dp),
                tint = if (isVpnRunning) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = isVpnRunning,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun VpnErrorBanner(
    error: VpnError,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val (titleRes, bodyRes, showSettings) = when (error) {
        VpnError.PERMISSION_REVOKED -> Triple(
            R.string.warning_vpn_revoked_title,
            R.string.warning_vpn_revoked_body,
            false
        )
        VpnError.ESTABLISH_FAILED -> Triple(
            R.string.warning_establish_failed_title,
            R.string.warning_establish_failed_body,
            false
        )
        VpnError.BOOT_START_RESTRICTED -> Triple(
            R.string.warning_boot_restricted_title,
            R.string.warning_boot_restricted_body,
            true
        )
        VpnError.NO_APPS_SELECTED -> Triple(
            R.string.msg_no_apps_selected,
            R.string.status_subtitle_off_none,
            false
        )
    }

    WarningBannerCard(
        icon = Icons.Filled.Warning,
        title = stringResource(titleRes),
        body = stringResource(bodyRes),
        isErrorStyle = true,
        primaryActionLabel = if (showSettings) stringResource(R.string.action_open_settings) else null,
        onPrimaryAction = if (showSettings) onOpenSettings else null,
        dismissActionLabel = stringResource(R.string.action_dismiss),
        onDismissAction = onDismiss
    )
}

@Composable
private fun WarningBannerCard(
    icon: ImageVector,
    title: String,
    body: String,
    isErrorStyle: Boolean,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    dismissActionLabel: String? = null,
    onDismissAction: (() -> Unit)? = null
) {
    val containerColor = if (isErrorStyle) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isErrorStyle) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall
            )
            if (primaryActionLabel != null || dismissActionLabel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (dismissActionLabel != null && onDismissAction != null) {
                        TextButton(onClick = onDismissAction) {
                            Text(text = dismissActionLabel, color = contentColor)
                        }
                    }
                    if (primaryActionLabel != null && onPrimaryAction != null) {
                        TextButton(onClick = onPrimaryAction) {
                            Text(
                                text = primaryActionLabel,
                                color = contentColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndFilterControls(
    searchQuery: String,
    showSystemApps: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onQuickPickOpen: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text(
                    text = stringResource(R.string.search_apps_hint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.clear_search_cd)
                        )
                    }
                }
            }
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            AssistChip(
                onClick = onQuickPickOpen,
                label = { Text(text = stringResource(R.string.quick_pick_chip)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onShowSystemAppsChange(!showSystemApps) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.show_system_apps),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = showSystemApps,
                onCheckedChange = onShowSystemAppsChange
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun AppListRow(
    app: AppInfo,
    isSelected: Boolean,
    loadAppIcon: suspend (String) -> Drawable?,
    onToggle: () -> Unit
) {
    val iconBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = app.packageName) {
        val drawable = loadAppIcon(app.packageName)
        value = if (drawable != null) {
            withContext(Dispatchers.Default) {
                try {
                    drawable.toBitmap(width = 96, height = 96).asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            null
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle),
        tonalElevation = if (isSelected) 2.dp else 0.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap!!,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_block),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.isSystemApp || app.isInWorkOrDualProfile) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (app.isSystemApp) {
                            SmallBadge(text = stringResource(R.string.badge_system))
                        }
                        if (app.isInWorkOrDualProfile) {
                            SmallBadge(text = stringResource(R.string.badge_work_dual_note))
                        }
                    }
                }
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

@Composable
private fun SmallBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun QuickPickDialog(
    allInstalledApps: List<AppInfo>,
    errorPackage: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val trimmed = input.trim()

    val suggestions = remember(trimmed, allInstalledApps) {
        if (trimmed.length < 2) {
            emptyList()
        } else {
            allInstalledApps.filter {
                it.packageName.contains(trimmed, ignoreCase = true) ||
                    it.label.contains(trimmed, ignoreCase = true)
            }.take(4)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.quick_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.quick_pick_description),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.quick_pick_label)) },
                    placeholder = { Text(text = stringResource(R.string.quick_pick_placeholder)) },
                    isError = errorPackage != null && errorPackage == trimmed
                )
                if (errorPackage != null && errorPackage == trimmed) {
                    Text(
                        text = stringResource(R.string.quick_pick_not_installed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (suggestions.isNotEmpty()) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        suggestions.forEach { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSubmit(app.packageName) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(input) },
                enabled = trimmed.isNotEmpty()
            ) {
                Text(text = stringResource(R.string.quick_pick_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    )
}
