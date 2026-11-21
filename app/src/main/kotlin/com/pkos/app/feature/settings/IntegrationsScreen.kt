package com.pkos.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pkos.app.R
import com.pkos.app.di.AppContainer
import com.pkos.app.di.ConnectionState
import com.pkos.app.di.IntegrationStatus
import com.pkos.app.ui.components.NoticeCard
import com.pkos.app.ui.components.StagedPill
import com.pkos.app.ui.components.StatusPill
import com.pkos.app.ui.format.Formatters
import com.pkos.app.ui.pkosViewModel
import com.pkos.app.ui.status.ToneKind
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.db.dao.SyncQueueCounts
import com.pkos.core.data.db.entity.BackupSnapshotEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(title: Int, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) { content() }
    }
}

@Composable
private fun ConnectionPill(state: ConnectionState) = when (state) {
    ConnectionState.NOT_CONNECTED -> StatusPill(stringResource(R.string.integration_not_connected), Icons.Outlined.CloudOff, ToneKind.NEUTRAL)
    ConnectionState.CONNECTED -> StatusPill(stringResource(R.string.status_synced), Icons.Outlined.Verified, ToneKind.SUCCESS)
    ConnectionState.ACTION_REQUIRED -> StatusPill(stringResource(R.string.status_action_required), Icons.Outlined.CloudOff, ToneKind.DANGER)
}

/** Honest connection status for Notion, Drive and AI (spec 2.7 "Integrations"). */
@Composable
fun IntegrationsScreen(status: IntegrationStatus, onBack: () -> Unit, openAiSettings: () -> Unit = {}, openNotionSettings: () -> Unit = {}, openBackup: () -> Unit = {}) {
    DetailScaffold(R.string.integrations_title, onBack) {
        IntegrationCard(Icons.Outlined.Description, R.string.integration_notion, R.string.integration_notion_body, status.notion, null, "integration_notion", openNotionSettings)
        IntegrationCard(Icons.Outlined.AddToDrive, R.string.integration_drive, R.string.integration_drive_body, status.drive, null, "integration_drive", openBackup)
        IntegrationCard(Icons.Outlined.Psychology, R.string.integration_ai, R.string.integration_ai_body, status.aiProvider, null, "integration_ai", openAiSettings)
    }
}

@Composable
private fun IntegrationCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, body: Int, state: ConnectionState, milestone: String?, tag: String,
    onSetup: (() -> Unit)? = null,
) {
    NoticeCard(
        icon, stringResource(title), stringResource(body),
        modifier = Modifier.testTag(tag),
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                ConnectionPill(state)
                milestone?.let { StagedPill(it) }
                onSetup?.let { androidx.compose.material3.TextButton(onClick = it, modifier = Modifier.testTag("${tag}_setup")) { Text(stringResource(R.string.integration_setup)) } }
            }
        },
    )
}

