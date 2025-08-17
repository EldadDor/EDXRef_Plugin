package com.github.edxref.settings

import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.github.edxref.query.settings.QueryRefSettingsComponent
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBTabbedPane
import javax.swing.JComponent

class EDXRefSettingsConfigurable : Configurable {
  private var tabbedPane: JBTabbedPane? = null
  private var wsConsumerComponent: WSConsumerSettingsComponent? = null
  private var queryRefComponent: QueryRefSettingsComponent? = null

  private val project: Project
    get() =
      ProjectManager.getInstance().openProjects.firstOrNull()
        ?: ProjectManager.getInstance().defaultProject

  override fun getDisplayName(): String = "EDXRef Plugin"

  override fun createComponent(): JComponent {
    wsConsumerComponent = WSConsumerSettingsComponent()
    queryRefComponent = QueryRefSettingsComponent()

    tabbedPane =
      JBTabbedPane().apply {
        addTab("WebService Consumer", wsConsumerComponent!!.panel)
        addTab("Query References", queryRefComponent!!.panel)
      }

    return tabbedPane!!
  }

  override fun isModified(): Boolean {
    val wsSettings = project.getWSConsumerSettings()
    val querySettings = project.getQueryRefSettings()

    val wsComponent = wsConsumerComponent ?: return false
    val queryComponent = queryRefComponent ?: return false

    // Check WSConsumer settings modifications
    val wsModified =
      wsComponent.enableLogging != wsSettings.enableLog ||
        wsComponent.invalidHosts != wsSettings.invalidHosts ||
        wsComponent.wsConsumerAnnotationFqn != wsSettings.wsConsumerAnnotationFqn ||
        wsComponent.webserviceConsumerFqn != wsSettings.webserviceConsumerFqn ||
        wsComponent.pearlWebserviceConsumerFqn != wsSettings.pearlWebserviceConsumerFqn ||
        wsComponent.wsParamAnnotationFqn != wsSettings.wsParamAnnotationFqn ||
        wsComponent.propertyAnnotationFqn != wsSettings.propertyAnnotationFqn ||
        wsComponent.validatePropertyAnnotations != wsSettings.validatePropertyAnnotations ||
        wsComponent.httpRequestAnnotationFqn != wsSettings.httpRequestAnnotationFqn ||
        wsComponent.wsHeaderAnnotationFqn != wsSettings.wsHeaderAnnotationFqn ||
        wsComponent.wsHeadersAnnotationFqn != wsSettings.wsHeadersAnnotationFqn ||
        wsComponent.internalApiAnnotationFqn != wsSettings.internalApiAnnotationFqn

    // Check QueryRef settings modifications
    val queryModified =
      queryComponent.queriesPath != querySettings.queriesPath ||
        queryComponent.sqlRefAnnotationFqn != querySettings.sqlRefAnnotationFqn ||
        queryComponent.sqlRefAnnotationAttributeName !=
          querySettings.sqlRefAnnotationAttributeName ||
        queryComponent.queryUtilsFqn != querySettings.queryUtilsFqn ||
        queryComponent.queryUtilsMethodName != querySettings.queryUtilsMethodName

    return wsModified || queryModified
  }

  override fun apply() {
    val wsSettings = project.getWSConsumerSettings()
    val querySettings = project.getQueryRefSettings()

    val wsComponent = wsConsumerComponent ?: return
    val queryComponent = queryRefComponent ?: return

    // Apply WSConsumer settings
    wsSettings.enableLog = wsComponent.enableLogging
    wsSettings.invalidHosts = wsComponent.invalidHosts
    wsSettings.wsConsumerAnnotationFqn = wsComponent.wsConsumerAnnotationFqn
    wsSettings.webserviceConsumerFqn = wsComponent.webserviceConsumerFqn
    wsSettings.pearlWebserviceConsumerFqn = wsComponent.pearlWebserviceConsumerFqn
    wsSettings.wsParamAnnotationFqn = wsComponent.wsParamAnnotationFqn
    wsSettings.propertyAnnotationFqn = wsComponent.propertyAnnotationFqn
    wsSettings.validatePropertyAnnotations = wsComponent.validatePropertyAnnotations
    wsSettings.httpRequestAnnotationFqn = wsComponent.httpRequestAnnotationFqn
    wsSettings.wsHeaderAnnotationFqn = wsComponent.wsHeaderAnnotationFqn
    wsSettings.wsHeadersAnnotationFqn = wsComponent.wsHeadersAnnotationFqn
    wsSettings.internalApiAnnotationFqn = wsComponent.internalApiAnnotationFqn

    // Apply QueryRef settings
    querySettings.queriesPath = queryComponent.queriesPath
    querySettings.sqlRefAnnotationFqn = queryComponent.sqlRefAnnotationFqn
    querySettings.sqlRefAnnotationAttributeName = queryComponent.sqlRefAnnotationAttributeName
    querySettings.queryUtilsFqn = queryComponent.queryUtilsFqn
    querySettings.queryUtilsMethodName = queryComponent.queryUtilsMethodName
  }

  override fun reset() {
    val wsSettings = project.getWSConsumerSettings()
    val querySettings = project.getQueryRefSettings()

    val wsComponent = wsConsumerComponent ?: return
    val queryComponent = queryRefComponent ?: return

    // Reset WSConsumer settings
    wsComponent.enableLogging = wsSettings.enableLog
    wsComponent.invalidHosts = wsSettings.invalidHosts
    wsComponent.wsConsumerAnnotationFqn = wsSettings.wsConsumerAnnotationFqn
    wsComponent.webserviceConsumerFqn = wsSettings.webserviceConsumerFqn
    wsComponent.pearlWebserviceConsumerFqn = wsSettings.pearlWebserviceConsumerFqn
    wsComponent.wsParamAnnotationFqn = wsSettings.wsParamAnnotationFqn
    wsComponent.propertyAnnotationFqn = wsSettings.propertyAnnotationFqn
    wsComponent.validatePropertyAnnotations = wsSettings.validatePropertyAnnotations
    wsComponent.httpRequestAnnotationFqn = wsSettings.httpRequestAnnotationFqn
    wsComponent.wsHeaderAnnotationFqn = wsSettings.wsHeaderAnnotationFqn
    wsComponent.wsHeadersAnnotationFqn = wsSettings.wsHeadersAnnotationFqn
    wsComponent.internalApiAnnotationFqn = wsSettings.internalApiAnnotationFqn

    // Reset QueryRef settings
    queryComponent.queriesPath = querySettings.queriesPath
    queryComponent.sqlRefAnnotationFqn = querySettings.sqlRefAnnotationFqn
    queryComponent.sqlRefAnnotationAttributeName = querySettings.sqlRefAnnotationAttributeName
    queryComponent.queryUtilsFqn = querySettings.queryUtilsFqn
    queryComponent.queryUtilsMethodName = querySettings.queryUtilsMethodName
  }

  override fun disposeUIResources() {
    wsConsumerComponent = null
    queryRefComponent = null
    tabbedPane = null
  }
}
