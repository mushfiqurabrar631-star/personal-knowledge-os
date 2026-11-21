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
@Serializable data class EditItemRoute(val id: String)
@Serializable data object TimelineRoute
@Serializable data object ActionsRoute
@Serializable data object CollectionsRoute
@Serializable data object ExportRoute
@Serializable data class CollectionDetailRoute(val id: String)
@Serializable data class CollectionEditRoute(val id: String? = null, val smart: Boolean = false)
@Serializable data class ConnectionsRoute(val id: String)
