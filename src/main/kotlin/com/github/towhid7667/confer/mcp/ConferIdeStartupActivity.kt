package com.github.towhid7667.confer.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts the local IDE MCP server as soon as the project opens — matching the real product's
 * behavior, where `claude` run from an external terminal can discover and connect to the IDE
 * without the chat panel ever having been opened.
 */
class ConferIdeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ConferIdeServerService>().ensureStarted()
    }
}
