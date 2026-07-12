package com.github.towhid7667.confer.toolWindow

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile

class ConferSessionVirtualFile(name: String = "Confer Chat") : LightVirtualFile(name, PlainTextFileType.INSTANCE, "") {
    override fun isWritable(): Boolean = false
}
