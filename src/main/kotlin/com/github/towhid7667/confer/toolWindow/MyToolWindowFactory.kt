package com.github.towhid7667.confer.toolWindow

import com.github.towhid7667.confer.claude.ClaudeEvent
import com.github.towhid7667.confer.claude.ClaudeService
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.LayeredIcon
import com.intellij.ui.content.ContentFactory
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

private class DotIcon(private val color: Color, private val diameter: Int = 6) : Icon {
    override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(x, y, diameter, diameter)
        g2.dispose()
    }
    override fun getIconWidth() = diameter
    override fun getIconHeight() = diameter
}

class ConferToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(
            ConferChatPanel(project),
            "",
            false,
        )
        toolWindow.contentManager.addContent(content)

        val baseIcon = IconLoader.getIcon("/icons/confer.svg", ConferToolWindowFactory::class.java)
        val dotIcon = LayeredIcon(baseIcon, DotIcon(Color(0xE5, 0xA4, 0x4C)))

        project.service<ClaudeService>().addListener { event ->
            if (toolWindow.isVisible) return@addListener
            when (event) {
                is ClaudeEvent.TurnEnd -> {
                    toolWindow.setIcon(dotIcon)
                    notify(project, toolWindow, "Claude finished responding", NotificationType.INFORMATION)
                }
                is ClaudeEvent.Error -> {
                    toolWindow.setIcon(dotIcon)
                    notify(project, toolWindow, "Confer: ${event.message}", NotificationType.ERROR)
                }
                else -> {}
            }
        }

        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
                    if (toolWindow.isVisible) toolWindow.setIcon(baseIcon)
                }
            },
        )
    }

    override fun shouldBeAvailable(project: Project) = true

    private fun notify(project: Project, toolWindow: ToolWindow, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Confer")
            .createNotification(text, type)
            .addAction(object : NotificationAction("Open Confer") {
                override fun actionPerformed(e: AnActionEvent, notification: com.intellij.notification.Notification) {
                    toolWindow.show()
                    notification.expire()
                }
            })
            .notify(project)
    }
}
