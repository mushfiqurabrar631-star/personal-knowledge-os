package com.pkos.app.feature.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.pkos.app.R
import com.pkos.app.ui.components.NoticeCard
import com.pkos.app.ui.components.StagedPill
import com.pkos.app.ui.theme.Spacing

/**
 * Ask AI shell (spec 2.7): grounded answers with citations need an AI provider (M3) and the knowledge index
 * (M6). Until then the input is disabled and the screen says exactly why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskAiScreen(onBack: () -> Unit, openIntegrations: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                NoticeCard(Icons.Outlined.FormatQuote, stringResource(R.string.ask_title), stringResource(R.string.ask_intro))
                NoticeCard(
                    Icons.Outlined.PowerOff,
                    stringResource(R.string.ask_not_ready_title),
                    stringResource(R.string.ask_not_ready_body),
                    trailing = { StagedPill("M3/M6") },
                    action = { OutlinedButton(onClick = openIntegrations) { Text(stringResource(R.string.ask_open_integrations)) } },
                    modifier = Modifier.testTag("ask_not_ready"),
                )
            }
            OutlinedTextField(
                value = "",
                onValueChange = {},
                enabled = false,
                placeholder = { Text(stringResource(R.string.ask_input_hint)) },
                trailingIcon = { Icon(Icons.AutoMirrored.Outlined.Send, null) },
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(Spacing.screen).testTag("ask_input"),
            )
        }
    }
}
