package com.github.edxref.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Persistent state for WSConsumer plugin settings.
 */
@State(
    name = "WSConsumerSettings",
    storages = [Storage("WSConsumerSettings.xml")]
)
@Service(Service.Level.PROJECT)
class WSConsumerSettings : PersistentStateComponent<WSConsumerSettings.State> {

    // Inner data class to hold the state
    data class State(
        var enableLog: Boolean = false,
        var invalidHosts: String = "msdevcz,msdevcrm,msdevbatch", // Default value
        var wsConsumerAnnotationFqn: String = "com.github.edxref.annotations.WSConsumer",
        var webserviceConsumerFqn: String = "com.github.edxref.annotations.WebserviceConsumer",
        var pearlWebserviceConsumerFqn: String = "com.github.edxref.annotations.PearlWebserviceConsumer",
        var wsParamAnnotationFqn: String = "com.github.edxref.annotations.WSParam",
        var propertyAnnotationFqn: String = "com.github.edxref.annotations.Property"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    // Helper properties to access settings
    var enableLog: Boolean
        get() = state.enableLog
        set(value) {
            state.enableLog = value
        }

    var invalidHosts: String
        get() = state.invalidHosts
        set(value) {
            state.invalidHosts = value
        }

    var wsConsumerAnnotationFqn: String
        get() = state.wsConsumerAnnotationFqn
        set(value) {
            state.wsConsumerAnnotationFqn = value
        }

    var webserviceConsumerFqn: String
        get() = state.webserviceConsumerFqn
        set(value) {
            state.webserviceConsumerFqn = value
        }

    var pearlWebserviceConsumerFqn: String
        get() = state.pearlWebserviceConsumerFqn
        set(value) {
            state.pearlWebserviceConsumerFqn = value
        }

    var wsParamAnnotationFqn: String
        get() = state.wsParamAnnotationFqn
        set(value) {
            state.wsParamAnnotationFqn = value
        }

    var propertyAnnotationFqn: String
        get() = state.propertyAnnotationFqn
        set(value) {
            state.propertyAnnotationFqn = value
        }

    companion object {
        // Extension function to get the service from a Project
        fun Project.getWSConsumerSettings() = this.service<WSConsumerSettings>()
    }
}
