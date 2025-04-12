package com.github.edxref.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JPanel

class WSConsumerSettingsComponent {
    // Store reference to the current project
    val project: Project = ProjectManager.getInstance().openProjects.firstOrNull()
        ?: ProjectManager.getInstance().defaultProject

    // UI components
    private val enableLoggingCheckbox = JBCheckBox("Enable logging (helps with debugging)")
    private val invalidHostsField = JBTextField()

    // Main panel
    val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Invalid hosts (comma separated):"), invalidHostsField, 1, false)
        .addComponent(enableLoggingCheckbox, 1)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    // Getters and setters for the settings values
    var enableLogging: Boolean
        get() = enableLoggingCheckbox.isSelected
        set(value) {
            enableLoggingCheckbox.isSelected = value
        }

    var invalidHosts: String
        get() = invalidHostsField.text
        set(value) {
            invalidHostsField.text = value
        }
}
