package com.amadeus.whale.network

import kotlinx.serialization.Serializable

@Serializable data class AmadeusSession(
  val id: String, val title: String, val mode: String,
  val updatedAt: Long, val lastMessage: String? = null,
)
@Serializable data class SessionListPayload(val sessions: List<AmadeusSession> = emptyList())
@Serializable data class SessionCreateBody(val title: String? = null, val workspaceId: String? = null)
@Serializable data class SessionCreatePayload(val session: AmadeusSession)
@Serializable data class StatusPayload(val capabilities: Capabilities = Capabilities())
@Serializable data class Capabilities(val sessions: Boolean = false, val reports: Boolean = false, val choices: Boolean = false)
@Serializable data class RenameBody(val title: String)
@Serializable data class PromptBody(val text: String)
@Serializable data class ReportPayload(val id: String, val title: String, val markdown: String, val createdAt: Long)
@Serializable data class PreviewPayload(val id: String, val type: String, val content: String, val title: String)
@Serializable data class ChoiceResolveBody(val choiceId: String, val selected: String)