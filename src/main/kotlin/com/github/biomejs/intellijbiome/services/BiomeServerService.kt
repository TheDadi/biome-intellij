@file:Suppress("UnstableApiUsage")

package com.github.biomejs.intellijbiome.services

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.intellij.codeStyle.AbstractConvertLineSeparatorsAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.platform.lsp.impl.LspServerImpl
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.util.LineSeparator
import org.eclipse.lsp4j.*
import java.util.*

@Service(Service.Level.PROJECT)
class BiomeServerService(private val project: Project) {
    private val groupId = "Biome"

    enum class Feature {
        Format, ApplySafeFixes, SortImports
    }

    companion object {
        fun getInstance(project: Project): BiomeServerService = project.getService(BiomeServerService::class.java)
    }

    private fun getServer(file: VirtualFile): LspServerImpl? =
        LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .firstOrNull { server -> server.descriptor.isSupportedFile(file) }
            .let { it as? LspServerImpl }

    suspend fun applySafeFixes(document: Document) {
        executeFeatures(document, EnumSet.of(Feature.ApplySafeFixes))
    }

    suspend fun sortImports(document: Document) {
        executeFeatures(document, EnumSet.of(Feature.SortImports))
    }

    suspend fun format(document: Document) {
        executeFeatures(document, EnumSet.of(Feature.Format))
    }

    fun restartBiomeServer() {
        LspServerManager.getInstance(project).stopAndRestartIfNeeded(BiomeLspServerSupportProvider::class.java)
    }

    fun stopBiomeServer() {
        LspServerManager.getInstance(project).stopServers(BiomeLspServerSupportProvider::class.java)
    }

    suspend fun executeFeatures(document: Document,
        features: EnumSet<Feature>) {
        val manager = FileDocumentManager.getInstance()
        val file = manager.getFile(document) ?: return
        val server = getServer(file) ?: return
        val commandName = BiomeBundle.message("biome.run.biome.check.with.features",
            features.joinToString(prefix = "(", postfix = ")") { it -> it.toString().lowercase() })

        val formattingActions = mutableListOf<Runnable>()

        if (features.contains(Feature.ApplySafeFixes) || features.contains(Feature.SortImports)) {
            val result = mutableListOf<LspIntentionAction>()

            if (features.contains(Feature.ApplySafeFixes)) {
                val codeActionParams = CodeActionParams(server.getDocumentIdentifier(file),
                    getLsp4jRange(document, 0, document.textLength),
                    CodeActionContext().apply {
                        diagnostics = emptyList()
                        only = listOf<String>("quickfix.biome")
                        triggerKind = CodeActionTriggerKind.Automatic
                    })

                val codeActionResults = server.sendRequest { it.textDocumentService.codeAction(codeActionParams) }
                codeActionResults?.forEach {
                    if (it.isRight) {
                        result.add(LspIntentionAction(server, it.right))
                    }
                }
            }

            if (features.contains(Feature.SortImports)) {
                val codeActionParams = CodeActionParams(server.getDocumentIdentifier(file),
                    getLsp4jRange(document, 0, document.textLength),
                    CodeActionContext().apply {
                        diagnostics = emptyList()
                        only = listOf<String>("source.organizeImports.biome")
                        triggerKind = CodeActionTriggerKind.Automatic
                    })

                val codeActionResults = server.sendRequest { it.textDocumentService.codeAction(codeActionParams) }
                codeActionResults?.forEach {
                    if (it.isRight) {
                        result.add(LspIntentionAction(server, it.right))
                    }
                }
            }

            WriteCommandAction.runWriteCommandAction(project, commandName, groupId, {
                result.forEach {
                    if (it.isAvailable()) {
                        it.invoke(null)
                    }
                }
            })
        }

        if (features.contains(Feature.Format)) {
            val formattingParams =
                DocumentFormattingParams(server.getDocumentIdentifier(file), FormattingOptions().apply {
                    tabSize = 2 // Biome doesn't use this information
                    isInsertSpaces = false // Biome doesn't use this information
                })

            val formattingResults = server.sendRequest { it.textDocumentService.formatting(formattingParams) }
            if (formattingResults == null) {
                return;
            }

            formattingActions.add(Runnable {
                var lineSeparator: LineSeparator? = null

                // To avoid getting incorrect offsets, we need to run the text edits in a reversed order.
                formattingResults.reverse();

                formattingResults.forEach {
                    val range = getRangeInDocument(document, it.range) ?: return@forEach

                    if (StringUtil.isEmpty(it.newText)) {
                        document.deleteString(range.startOffset, range.endOffset)
                    } else {
                        val normalizedText = StringUtil.convertLineSeparators(it.newText)

                        if (range.endOffset >= 0) {
                            if (range.length <= 0) {
                                document.insertString(range.startOffset, normalizedText)
                            } else {
                                document.replaceString(range.startOffset, range.endOffset, normalizedText)
                            }
                        } else if (range.startOffset > 0) {
                            document.insertString(range.startOffset, normalizedText)
                        } else if (!StringUtil.equals(document.charsSequence, normalizedText)) {
                            document.setText(normalizedText)
                        }
                    }

                    StringUtil.detectSeparators(it.newText)?.apply {
                        lineSeparator = this
                    }
                }

                if (lineSeparator != null) {
                    setDetectedLineSeparator(project, file, lineSeparator)
                }
            })

            WriteCommandAction.runWriteCommandAction(project, commandName, groupId, {
                formattingActions.forEach { it.run() }
            })
        }
    }

    private fun setDetectedLineSeparator(
        project: Project,
        vFile: VirtualFile,
        newSeparator: LineSeparator?,
    ): Boolean {
        if (newSeparator != null) {
            val newSeparatorString: String = newSeparator.separatorString

            if (!StringUtil.equals(vFile.detectedLineSeparator, newSeparatorString)) {
                AbstractConvertLineSeparatorsAction.changeLineSeparators(project, vFile, newSeparatorString)
                return true
            }
        }
        return false
    }

    fun notifyRestart() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Biome")
            .createNotification(
                BiomeBundle.message("biome.language.server.restarted"),
                "",
                NotificationType.INFORMATION
            )
            .notify(project)
    }
}
