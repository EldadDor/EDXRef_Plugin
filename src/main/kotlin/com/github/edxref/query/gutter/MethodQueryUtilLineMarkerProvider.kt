package com.github.edxref.query.gutter

import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.cache.QueryIndexService
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression

class MethodQueryUtilLineMarkerProvider : LineMarkerProvider {
    private val log = logger<MethodQueryUtilLineMarkerProvider>()
    private fun getSettings(project: Project) = project.getQueryRefSettings()
    private fun getQueryUtilsFqn(project: Project) = getSettings(project).queryUtilsFqn.ifBlank { "com.example.QueryUtils" }
    private fun getQueryUtilsMethodName(project: Project) = getSettings(project).queryUtilsMethodName.ifBlank { "getQuery" }

    override fun getLineMarkerInfo(p0: PsiElement): LineMarkerInfo<*>? {
        return null
    }

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (elements.isEmpty() || DumbService.isDumb(elements.first().project)) {
            log.debug("Skipping Java markers: No elements or in dumb mode.")
            return
        }
        for (element in elements) {
            // Only interested in string literals
            if (element is PsiLiteralExpression && element.value is String) {
                val literalValue = element.value as String

                // Check if parent is a method call: queryUtils.getQuery("...")
                val methodCall = element.parent?.parent as? PsiMethodCallExpression
                if (methodCall != null) {
                    val methodExpr = methodCall.methodExpression
                    val methodName = methodExpr.referenceName
                    val qualifier = methodExpr.qualifierExpression?.text

                    if (methodName == getQueryUtilsMethodName(element.project) && qualifier == getQueryUtilsFqn(element.project)) {
                        // Now, literalValue is your query ID
                        val xmlTag = QueryIndexService.getInstance(element.project).findXmlTagById(literalValue)
                        if (xmlTag != null) {
                            val builder = NavigationGutterIconBuilder.create(EDXRefIcons.METHOD_JAVA__TO_XML).setTargets(xmlTag).setTooltipText("Navigate to Query XML definition").setAlignment(GutterIconRenderer.Alignment.LEFT)
                            result.add(builder.createLineMarkerInfo(element))
                        }
                    }
                }
            }
        }
    }

}