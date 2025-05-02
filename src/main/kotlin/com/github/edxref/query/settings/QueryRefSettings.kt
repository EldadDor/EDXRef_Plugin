package com.github.edxref.query.settings

import com.github.edxref.settings.WSConsumerSettings
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@State(
    name = "QueryRefSettings", storages = [Storage("QueryRefSettings.xml")]
)
@Service(Service.Level.PROJECT)
class QueryRefSettings : PersistentStateComponent<QueryRefSettings.State> {

    data class State(
        var queriesPath: String = "resources/queries/",
        var sqlRefAnnotationFqn: String = "com.github.edxref.annotations.SQLRef",
        var sqlRefAnnotationAttributeName: String = "refId",
        var queryUtilsFqn: String = "com.github.edxref.QueryUtils",
        var queryUtilsMethodName: String = "getQuery"
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        // Extension function to get the service from a Project
        fun Project.getQueryRefSettings() = this.service<QueryRefSettings>()
    }

    var queriesPath: String
        get() = state.queriesPath
        set(value) {
            state.queriesPath = value
        }

    var sqlRefAnnotationFqn: String
        get() = state.sqlRefAnnotationFqn
        set(value) {
            state.sqlRefAnnotationFqn = value
        }

    var sqlRefAnnotationAttributeName: String // New
        get() = state.sqlRefAnnotationAttributeName
        set(value) {
            state.sqlRefAnnotationAttributeName = value
        }
    var queryUtilsFqn: String
        get() = state.queryUtilsFqn
        set(value) {
            state.queryUtilsFqn = value
        }
    var queryUtilsMethodName: String
        get() = state.queryUtilsMethodName
        set(value) {
            state.queryUtilsMethodName = value
        }
}