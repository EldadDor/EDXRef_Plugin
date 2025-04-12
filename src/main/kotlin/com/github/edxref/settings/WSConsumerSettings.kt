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
        var invalidHosts: String = "msdevcz,msdevcrm,msdevbatch" // Default value
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

    companion object {
        // Extension function to get the service from a Project
        fun Project.getWSConsumerSettings() = this.service<WSConsumerSettings>()
    }
}
