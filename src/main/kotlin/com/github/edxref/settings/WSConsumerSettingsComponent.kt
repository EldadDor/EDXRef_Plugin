package com.github.edxref.settings

import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import javax.swing.JPanel

class WSConsumerSettingsComponent {
    // Store reference to the current project
    val project: Project = ProjectManager.getInstance().openProjects.firstOrNull()
        ?: ProjectManager.getInstance().defaultProject

    // Settings object
    private val settings = project.getWSConsumerSettings()

    // Advanced panel using UI DSL
    val panel: DialogPanel = panel {
        group("General Settings") {
            row {
                checkBox("Enable logging")
                    .bindSelected({ settings.enableLog }, { settings.enableLog = it })
                    .comment("When enabled, the plugin will log detailed information for debugging purposes")
            }
        }

        group("Inspection Settings") {
            row {
                label("Invalid hosts (comma separated):")
                textField()
                    .bindText({ settings.invalidHosts }, { settings.invalidHosts = it })
                    .comment("List of host names that should be considered invalid in URLs")
                    .columns(40)
            }
        }
    }

    // Simple property accessors for compatibility with the Configurable
    var enableLogging: Boolean
        get() = settings.enableLog
        set(value) {
            settings.enableLog = value
        }

    var invalidHosts: String
        get() = settings.invalidHosts
        set(value) {
            settings.invalidHosts = value
        }
}
