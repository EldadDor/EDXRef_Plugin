package com.github.edxref.query.settings // Or your appropriate package

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JPanel

/**
 * Represents the UI panel for QueryRef settings.
 * Contains the Swing components users interact with.
 */
class QueryRefSettingsComponent {

    val project: Project = ProjectManager.getInstance().openProjects.firstOrNull()
        ?: ProjectManager.getInstance().defaultProject


    val panel: JPanel
    private val queriesPathField = JBTextField()
    private val sqlRefAnnotationFqnField = JBTextField()
    private val sqlRefAnnotationAttributeNameField = JBTextField()

    init {
        // Use FormBuilder for standard IntelliJ settings layout
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Queries Path Pattern:"), queriesPathField, 1, false)
            .addTooltip("Glob pattern for query XML file locations (e.g., **/resources/queries/*-queries.xml)")
            .addLabeledComponent(JBLabel("SQLRef Annotation FQN:"), sqlRefAnnotationFqnField, 1, false)
            .addTooltip("Fully qualified name of the annotation marking query references (e.g., com.example.SqlRef)")
            .addLabeledComponent(JBLabel("SQLRef Annotation Attribute:"), sqlRefAnnotationAttributeNameField, 1, false)
            .addTooltip("Name of the annotation attribute holding the query ID (e.g., refId or value)")
            .addComponentFillVertically(JPanel(), 0) // Add vertical space
            .panel
    }

    var queriesPath: String
        get() = queriesPathField.text
        set(value) {
            queriesPathField.text = value
        }

    var sqlRefAnnotationFqn: String
        get() = sqlRefAnnotationFqnField.text
        set(value) {
            sqlRefAnnotationFqnField.text = value
        }

    var sqlRefAnnotationAttributeName: String // New
        get() = sqlRefAnnotationAttributeNameField.text
        set(value) {
            sqlRefAnnotationAttributeNameField.text = value
        }
}
