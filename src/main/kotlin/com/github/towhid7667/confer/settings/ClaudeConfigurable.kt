package com.github.towhid7667.confer.settings

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.awt.Desktop
import java.io.File
import javax.swing.JComponent

private val MODEL_OPTIONS = listOf("default", "sonnet", "opus", "haiku", "fable")

class ClaudeConfigurable : Configurable {

    private val binaryPathField = JBTextField()
    private val testResultLabel = JBLabel(" ")
    private val modelField = com.intellij.openapi.ui.ComboBox(MODEL_OPTIONS.toTypedArray())
    private val ctrlEnterCheck = JBCheckBox("Use Ctrl+Enter to send (Enter inserts a newline)")
    private val gitIgnoreCheck = JBCheckBox("Respect .gitignore when searching files for @-mentions")
    private val bypassCheck = JBCheckBox("Allow \"Bypass permissions\" mode")
    private val autosaveCheck = JBCheckBox("Save all open files before sending a message")
    private val hideOnboardingCheck = JBCheckBox("Hide the onboarding checklist")
    private val envVarsArea = JBTextArea(4, 40)

    override fun getDisplayName(): String = "Confer"

    override fun createComponent(): JComponent = panel {
        row("Claude binary path:") {
            cell(binaryPathField).resizableColumn()
            button("Browse…") { browseForBinary() }
            button("Test") { testBinary() }
        }
        row("") {
            cell(testResultLabel)
        }
        row("Model:") {
            cell(modelField)
                .comment("Passed as --model. \"default\" omits the flag and uses the CLI's own default.")
        }
        row { cell(ctrlEnterCheck) }
        row { cell(gitIgnoreCheck) }
        row { cell(autosaveCheck) }
        row { cell(hideOnboardingCheck) }
        row { cell(bypassCheck) }
        row {
            cell(JBLabel("Bypass permissions skips ALL tool-use confirmations. Only enable this in a sandbox you trust."))
        }
        row("Environment variables:") {
            cell(JBScrollPane(envVarsArea))
                .comment("One KEY=VALUE per line. Merged into the claude process environment.")
        }
        row {
            button("Open ~/.claude/settings.json") { openClaudeSettingsJson() }
                .comment("This file is shared with the CLI directly — Confer does not duplicate hooks/permissions/MCP config here.")
        }
    }.also { reset() }

    private fun browseForBinary() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
        val chosen = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, null, null)
            .choose(null)
            .firstOrNull()
        if (chosen != null) binaryPathField.text = chosen.path
    }

    private fun testBinary() {
        testResultLabel.text = "Running…"
        try {
            val cmd = GeneralCommandLine(binaryPathField.text).withParameters("--version")
            val output = ExecUtil.execAndGetOutput(cmd, 5000)
            testResultLabel.text = if (output.exitCode == 0) {
                "✓ ${output.stdout.trim()}"
            } else {
                "✗ Exit code ${output.exitCode}: ${output.stderr.trim()}"
            }
        } catch (e: Exception) {
            testResultLabel.text = "✗ ${e.message}"
        }
    }

    private fun openClaudeSettingsJson() {
        val file = File(System.getProperty("user.home"), ".claude/settings.json")
        if (!file.exists()) {
            testResultLabel.text = "✗ ${file.path} does not exist yet"
            return
        }
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
    }

    override fun isModified(): Boolean {
        val s = ClaudeSettings.getInstance()
        return binaryPathField.text != s.claudeBinaryPath ||
            modelField.item != s.model ||
            ctrlEnterCheck.isSelected != s.useCtrlEnterToSend ||
            gitIgnoreCheck.isSelected != s.respectGitIgnore ||
            bypassCheck.isSelected != s.allowDangerouslySkipPermissions ||
            autosaveCheck.isSelected != s.autosave ||
            hideOnboardingCheck.isSelected != s.hideOnboarding ||
            envVarsArea.text != s.environmentVariables
    }

    override fun apply() {
        val s = ClaudeSettings.getInstance()
        s.claudeBinaryPath = binaryPathField.text
        s.model = modelField.item ?: "default"
        s.useCtrlEnterToSend = ctrlEnterCheck.isSelected
        s.respectGitIgnore = gitIgnoreCheck.isSelected
        s.allowDangerouslySkipPermissions = bypassCheck.isSelected
        s.autosave = autosaveCheck.isSelected
        s.hideOnboarding = hideOnboardingCheck.isSelected
        s.environmentVariables = envVarsArea.text
    }

    override fun reset() {
        val s = ClaudeSettings.getInstance()
        binaryPathField.text = s.claudeBinaryPath
        modelField.item = s.model
        ctrlEnterCheck.isSelected = s.useCtrlEnterToSend
        gitIgnoreCheck.isSelected = s.respectGitIgnore
        bypassCheck.isSelected = s.allowDangerouslySkipPermissions
        autosaveCheck.isSelected = s.autosave
        hideOnboardingCheck.isSelected = s.hideOnboarding
        envVarsArea.text = s.environmentVariables
        testResultLabel.text = " "
    }
}
