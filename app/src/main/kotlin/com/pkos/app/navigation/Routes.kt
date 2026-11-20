package com.pkos.app.navigation

import kotlinx.serialization.Serializable

@Serializable data object OnboardingRoute
@Serializable data object HomeRoute
@Serializable data object InboxRoute
@Serializable data object SearchRoute
@Serializable data object SettingsRoute
@Serializable data object TopicsRoute
@Serializable data object AskAiRoute
@Serializable data object IntegrationsRoute
@Serializable data object BackupSyncRoute
@Serializable data object AiSettingsRoute
@Serializable data object NotionSettingsRoute
@Serializable data class ItemDetailRoute(val id: String)
