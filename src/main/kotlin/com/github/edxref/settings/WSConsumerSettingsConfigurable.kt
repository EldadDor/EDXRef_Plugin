package com.github.edxref.settings

import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class WSConsumerSettingsConfigurable : Configurable {
    private var settingsComponent: WSConsumerSettingsComponent? = null

    override fun getDisplayName(): String = "WSConsumer Settings"

    override fun createComponent(): JComponent {
        settingsComponent = WSConsumerSettingsComponent()
        return settingsComponent!!.panel
    }

    override fun isModified(): Boolean {
        val settings = settingsComponent?.project?.getWSConsumerSettings() ?: return false
        return settingsComponent!!.enableLogging != settings.enableLog ||
                settingsComponent!!.invalidHosts != settings.invalidHosts
    }

    override fun apply() {
        val settings = settingsComponent?.project?.getWSConsumerSettings() ?: return
        settings.enableLog = settingsComponent!!.enableLogging
        settings.invalidHosts = settingsComponent!!.invalidHosts
    }

    override fun reset() {
        val settings = settingsComponent?.project?.getWSConsumerSettings() ?: return
        settingsComponent!!.enableLogging = settings.enableLog
        settingsComponent!!.invalidHosts = settings.invalidHosts
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }
}
