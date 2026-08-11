package com.github.towhid7667.confer.statusbar

import com.github.towhid7667.confer.claude.ClaudeEvent
import com.github.towhid7667.confer.claude.ClaudeSessionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

private const val WIDGET_ID = "ConferStatusBarWidget"

class ConferStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = "Confer"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = ConferStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}

class ConferStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {

    private var statusBar: StatusBar? = null
    private var thinking = false

    private val idleIcon = IconLoader.getIcon("/icons/confer.svg", ConferStatusBarWidget::class.java)
    private val thinkingIcon = AnimatedIcon.Default()

    private val listener = ClaudeEventListenerAdapter()

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        mainSession().addListener(listener)
    }

    override fun dispose() {
        mainSession().removeListener(listener)
        statusBar = null
    }

    private fun mainSession() =
        project.service<ClaudeSessionManager>().getOrCreateSession(ClaudeSessionManager.DEFAULT_TAB_ID)

    override fun getTooltipText(): String =
        if (thinking) "Confer — Claude is working…" else "Confer — click to open"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("Confer")?.show()
    }

    override fun getIcon(): Icon = if (thinking) thinkingIcon else idleIcon

    private inner class ClaudeEventListenerAdapter : com.github.towhid7667.confer.claude.ClaudeEventListener {
        override fun onEvent(event: ClaudeEvent) {
            val wasThinking = thinking
            thinking = when (event) {
                is ClaudeEvent.TurnEnd, is ClaudeEvent.Error -> false
                is ClaudeEvent.TextDelta, is ClaudeEvent.ThinkingDelta, is ClaudeEvent.ToolStart -> true
                else -> thinking
            }
            if (thinking != wasThinking) statusBar?.updateWidget(WIDGET_ID)
        }
    }
}
