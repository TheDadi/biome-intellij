package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomePackage
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveComment
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.platform.ide.progress.runWithModalProgressBlocking

class BiomeOnSaveFormatActionInfo(actionOnSaveContext: ActionOnSaveContext) :
    ActionOnSaveBackedByOwnConfigurable<BiomeConfigurable>(
        actionOnSaveContext,
        BiomeConfigurable.CONFIGURABLE_ID,
        BiomeConfigurable::class.java
    ) {

    override fun getActionOnSaveName() =
        BiomeBundle.message("biome.run.format.on.save.checkbox.on.actions.on.save.page")

    override fun isApplicableAccordingToStoredState(): Boolean =
        BiomeSettings.getInstance(project).configurationMode != ConfigurationMode.DISABLED

    override fun isActionOnSaveEnabledAccordingToStoredState() = BiomeSettings.getInstance(project).formatOnSave

    override fun isApplicableAccordingToUiState(configurable: BiomeConfigurable): Boolean =
        !configurable.disabledConfiguration.isSelected

    override fun isActionOnSaveEnabledAccordingToUiState(configurable: BiomeConfigurable) =
        configurable.runFormatOnSaveCheckBox.isSelected

    override fun setActionOnSaveEnabled(configurable: BiomeConfigurable, enabled: Boolean) {
        configurable.runFormatOnSaveCheckBox.isSelected = enabled
    }

    override fun getActionLinks() = listOf(createGoToPageInSettingsLink(BiomeConfigurable.CONFIGURABLE_ID))

    override fun getCommentAccordingToUiState(configurable: BiomeConfigurable): ActionOnSaveComment? {
        if (!isSaveActionApplicable) return ActionOnSaveComment.info(BiomeBundle.message("biome.on.save.comment.disabled"))

        val biomePackage = BiomePackage(project)
        val version = runWithModalProgressBlocking(project, BiomeBundle.message("biome.version")) {
            biomePackage.versionNumber()
        }
        return ActionInfo.defaultComment(version, configurable.runForFilesField.text.trim(), isActionOnSaveEnabled)
    }

    override fun getCommentAccordingToStoredState(): ActionOnSaveComment? {
        if (!isSaveActionApplicable) return ActionOnSaveComment.info(BiomeBundle.message("biome.on.save.comment.disabled"))

        val biomePackage = BiomePackage(project)
        val settings = BiomeSettings.getInstance(project)
        val version = runWithModalProgressBlocking(project, BiomeBundle.message("biome.version")) {
            biomePackage.versionNumber()
        }

        return ActionInfo.defaultComment(version, settings.filePattern, isActionOnSaveEnabled)
    }
}
