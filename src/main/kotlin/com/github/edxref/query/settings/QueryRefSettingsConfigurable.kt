package com.github.edxref.query.settings // Or your appropriate package

import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

/**
 * Provides controller functionality for QueryRef settings.
 * Bridges the UI component with the persistent settings state.
 */
class QueryRefSettingsConfigurable : Configurable {

    private var settingsComponent: QueryRefSettingsComponent? = null

    @Nls(capitalization = Nls.Capitalization.Title)
    override fun getDisplayName(): String {
        return "QueryRef Settings" // Name shown in the Settings tree
    }


    override fun createComponent(): JComponent? {
        settingsComponent = QueryRefSettingsComponent()
        return settingsComponent?.panel
    }

    /**
     * Checks if the settings in the UI differ from the stored settings.
     */
    override fun isModified(): Boolean {
        val settings = settingsComponent?.project?.getQueryRefSettings() ?: return false
        return settingsComponent!!.queriesPath != settings.queriesPath ||
                settingsComponent!!.sqlRefAnnotationFqn != settings.sqlRefAnnotationFqn ||
                settingsComponent!!.sqlRefAnnotationAttributeName != settings.sqlRefAnnotationAttributeName
    }

    /**
     * Saves the settings from the UI to the persistent state.
     */
    override fun apply() {
        val settings = settingsComponent?.project?.getQueryRefSettings() ?: return
        settings.queriesPath = settingsComponent!!.queriesPath
        settings.sqlRefAnnotationFqn = settingsComponent!!.sqlRefAnnotationFqn
        settings.sqlRefAnnotationAttributeName = settingsComponent!!.sqlRefAnnotationAttributeName
    }

    override fun reset() {
        val settings = settingsComponent?.project?.getQueryRefSettings() ?: return
        settingsComponent!!.queriesPath = settings.queriesPath
        settingsComponent!!.sqlRefAnnotationFqn = settings.sqlRefAnnotationFqn
        settingsComponent!!.sqlRefAnnotationAttributeName = settings.sqlRefAnnotationAttributeName
    }

    override fun disposeUIResources() {
        settingsComponent = null // Release reference to the UI component
    }

}
