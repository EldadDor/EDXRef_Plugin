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
                settingsComponent!!.invalidHosts != settings.invalidHosts ||
                settingsComponent!!.wsConsumerAnnotationFqn != settings.wsConsumerAnnotationFqn ||
                settingsComponent!!.webserviceConsumerFqn != settings.webserviceConsumerFqn ||
                settingsComponent!!.pearlWebserviceConsumerFqn != settings.pearlWebserviceConsumerFqn ||
                settingsComponent!!.wsParamAnnotationFqn != settings.wsParamAnnotationFqn ||
                settingsComponent!!.propertyAnnotationFqn != settings.propertyAnnotationFqn ||
                settingsComponent!!.validatePropertyAnnotations != settings.validatePropertyAnnotations ||
                settingsComponent!!.httpRequestAnnotationFqn != settings.httpRequestAnnotationFqn ||
                settingsComponent!!.wsHeaderAnnotationFqn != settings.wsHeaderAnnotationFqn ||
                settingsComponent!!.wsHeadersAnnotationFqn != settings.wsHeadersAnnotationFqn
    }

    override fun apply() {
        val settings = settingsComponent?.project?.getWSConsumerSettings() ?: return
        settings.enableLog = settingsComponent!!.enableLogging
        settings.invalidHosts = settingsComponent!!.invalidHosts
        settings.wsConsumerAnnotationFqn = settingsComponent!!.wsConsumerAnnotationFqn
        settings.webserviceConsumerFqn = settingsComponent!!.webserviceConsumerFqn
        settings.pearlWebserviceConsumerFqn = settingsComponent!!.pearlWebserviceConsumerFqn
        settings.wsParamAnnotationFqn = settingsComponent!!.wsParamAnnotationFqn
        settings.propertyAnnotationFqn = settingsComponent!!.propertyAnnotationFqn
        settings.validatePropertyAnnotations = settingsComponent!!.validatePropertyAnnotations
        settings.httpRequestAnnotationFqn = settingsComponent!!.httpRequestAnnotationFqn
        settings.wsHeaderAnnotationFqn = settingsComponent!!.wsHeaderAnnotationFqn
        settings.wsHeadersAnnotationFqn = settingsComponent!!.wsHeadersAnnotationFqn
    }

    override fun reset() {
        val settings = settingsComponent?.project?.getWSConsumerSettings() ?: return
        settingsComponent!!.enableLogging = settings.enableLog
        settingsComponent!!.invalidHosts = settings.invalidHosts
        settingsComponent!!.wsConsumerAnnotationFqn = settings.wsConsumerAnnotationFqn
        settingsComponent!!.webserviceConsumerFqn = settings.webserviceConsumerFqn
        settingsComponent!!.pearlWebserviceConsumerFqn = settings.pearlWebserviceConsumerFqn
        settingsComponent!!.wsParamAnnotationFqn = settings.wsParamAnnotationFqn
        settingsComponent!!.propertyAnnotationFqn = settings.propertyAnnotationFqn
        settingsComponent!!.validatePropertyAnnotations = settings.validatePropertyAnnotations
        settingsComponent!!.httpRequestAnnotationFqn = settings.httpRequestAnnotationFqn // New setting
    }


    override fun disposeUIResources() {
        settingsComponent = null
    }
}
