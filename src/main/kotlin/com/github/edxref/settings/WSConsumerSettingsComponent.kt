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
    private val wsConsumerAnnotationField = JBTextField()
    private val webserviceConsumerField = JBTextField()
    private val wsParamAnnotationField = JBTextField()
    private val propertyAnnotationField = JBTextField()

    // Main panel
    val panel: JPanel = FormBuilder.createFormBuilder()
        .addComponent(enableLoggingCheckbox)
        .addLabeledComponent(JBLabel("Invalid hosts (comma separated):"), invalidHostsField, 1, false)
        .addLabeledComponent(JBLabel("WSConsumer Annotation FQN:"), wsConsumerAnnotationField, 1, false)
        .addLabeledComponent(JBLabel("WebserviceConsumer Interface FQN:"), webserviceConsumerField, 1, false)
        .addLabeledComponent(JBLabel("WSParam Annotation FQN:"), wsParamAnnotationField, 1, false)
        .addLabeledComponent(JBLabel("Property Annotation FQN:"), propertyAnnotationField, 1, false)
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

    var wsConsumerAnnotationFqn: String
        get() = wsConsumerAnnotationField.text
        set(value) {
            wsConsumerAnnotationField.text = value
        }

    var webserviceConsumerFqn: String
        get() = webserviceConsumerField.text
        set(value) {
            webserviceConsumerField.text = value
        }

    var wsParamAnnotationFqn: String
        get() = wsParamAnnotationField.text
        set(value) {
            wsParamAnnotationField.text = value
        }

    var propertyAnnotationFqn: String
        get() = propertyAnnotationField.text
        set(value) {
            propertyAnnotationField.text = value
        }
}
