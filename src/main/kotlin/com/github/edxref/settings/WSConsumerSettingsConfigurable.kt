package com.github.edxref.settings

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
        return settingsComponent?.panel?.isModified() ?: false
    }

    override fun apply() {
        settingsComponent?.panel?.apply()
    }

    override fun reset() {
        settingsComponent?.panel?.reset()
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }
}
