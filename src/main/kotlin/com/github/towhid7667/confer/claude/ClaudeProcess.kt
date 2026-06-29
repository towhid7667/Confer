package com.github.towhid7667.confer.claude

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Key
import com.intellij.util.EnvironmentUtil
import java.io.OutputStreamWriter
import java.io.PrintWriter

class ClaudeProcess(
    binaryPath: String,
    workingDir: String,
    // ⚠️ Verify --permission-mode flag name and accepted values against `claude --help`
    permissionMode: String = "default",
) : Disposable {

    private val handler: OSProcessHandler
    private val writer: PrintWriter
    private val lineBuffer = StringBuilder()

    init {
        val params = buildList {
            add("--output-format"); add("stream-json")
            add("--input-format");  add("stream-json")
            add("--verbose")
            if (permissionMode != "default") {
                add("--permission-mode"); add(permissionMode)
            }
        }

        val cmd = GeneralCommandLine(binaryPath)
            .withParameters(*params.toTypedArray())
            .withWorkDirectory(workingDir)
            .withEnvironment(EnvironmentUtil.getEnvironmentMap())
            .withCharset(Charsets.UTF_8)

        handler = OSProcessHandler(cmd)
        writer = PrintWriter(OutputStreamWriter(handler.process.outputStream, Charsets.UTF_8), true)
    }

    fun start(onLine: (String) -> Unit) {
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputType.STDOUT) return
                synchronized(lineBuffer) {
                    lineBuffer.append(event.text)
                    drainLines(onLine)
                }
            }
        })
        handler.startNotify()
    }

    private fun drainLines(onLine: (String) -> Unit) {
        val content = lineBuffer.toString()
        val lastNl = content.lastIndexOf('\n')
        if (lastNl < 0) return
        val complete = content.substring(0, lastNl)
        lineBuffer.clear()
        lineBuffer.append(content.substring(lastNl + 1))
        complete.split('\n').filter { it.isNotBlank() }.forEach(onLine)
    }

    fun writeMessage(json: String) {
        writer.println(json)
        writer.flush()
    }

    fun sendInterrupt() {
        handler.destroyProcess()
    }

    override fun dispose() {
        if (!handler.isProcessTerminated) {
            handler.destroyProcess()
        }
        writer.close()
    }
}
