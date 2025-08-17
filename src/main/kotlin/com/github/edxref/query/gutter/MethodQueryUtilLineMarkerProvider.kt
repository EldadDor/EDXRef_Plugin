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
import com.intellij.psi.PsiReferenceExpression

class MethodQueryUtilLineMarkerProvider : LineMarkerProvider {
  private val log = logger<MethodQueryUtilLineMarkerProvider>()

  private fun getSettings(project: Project) = project.getQueryRefSettings()

  private fun getQueryUtilsFqn(project: Project) =
    getSettings(project).queryUtilsFqn.ifBlank { "com.example.QueryUtils" }

  private fun getQueryUtilsMethodName(project: Project) =
    getSettings(project).queryUtilsMethodName.ifBlank { "getQuery" }

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Fast path for method call string literals
    if (element is PsiLiteralExpression && element.value is String) {
      return createLineMarkerFromCache(element)
    }
    return null
  }

  private fun createLineMarkerFromCache(element: PsiLiteralExpression): LineMarkerInfo<*>? {
    val project = element.project

    // Check if we're in dumb mode - return null to defer to collectSlowLineMarkers
    if (DumbService.isDumb(project)) {
      return null
    }

    val literalValue = element.value as String

    // Quick validation - check if parent is a method call
    val methodCall = element.parent?.parent as? PsiMethodCallExpression ?: return null
    val methodExpr = methodCall.methodExpression
    val methodName = methodExpr.referenceName
    val qualifierExpr = methodExpr.qualifierExpression as? PsiReferenceExpression

    // Fast check for method name match
    if (methodName != getQueryUtilsMethodName(project)) {
      return null
    }

    // Fast check for qualifier type
    val qualifierType = qualifierExpr?.type
    val qualifierFqn = qualifierType?.canonicalText
    if (qualifierFqn != getQueryUtilsFqn(project)) {
      return null
    }

    try {
      // Use cache to find XML tag
      val xmlTag = QueryIndexService.getInstance(project).findXmlTagById(literalValue)
      if (xmlTag != null) {
        log.debug("Fast path: Found XML tag for queryId '$literalValue'")

        val builder =
          NavigationGutterIconBuilder.create(EDXRefIcons.METHOD_JAVA__TO_XML)
            .setTargets(xmlTag)
            .setTooltipText("Navigate to Query XML definition")
            .setAlignment(GutterIconRenderer.Alignment.LEFT)
        return builder.createLineMarkerInfo(element)
      }
    } catch (e: Exception) {
      // If anything goes wrong in the fast path, fall back to slow path
      log.debug("Fast path failed for queryId '$literalValue', will use slow path: ${e.message}")
      return null
    }

    return null
  }

  override fun collectSlowLineMarkers(
    elements: List<PsiElement>,
    result: MutableCollection<in LineMarkerInfo<*>>,
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
          val qualifierExpr = methodExpr.qualifierExpression as? PsiReferenceExpression

          if (methodName == getQueryUtilsMethodName(element.project)) {
            // Check if the qualifier resolves to the expected class type
            val qualifierType = qualifierExpr?.type
            val qualifierFqn = qualifierType?.canonicalText

            if (qualifierFqn == getQueryUtilsFqn(element.project)) {
              // Now, literalValue is your query ID
              val xmlTag =
                QueryIndexService.getInstance(element.project).findXmlTagById(literalValue)
              if (xmlTag != null) {
                val builder =
                  NavigationGutterIconBuilder.create(EDXRefIcons.METHOD_JAVA__TO_XML)
                    .setTargets(xmlTag)
                    .setTooltipText("Navigate to Query XML definition")
                    .setAlignment(GutterIconRenderer.Alignment.LEFT)
                result.add(builder.createLineMarkerInfo(element))
              }
            }
          }
        }
      }
    }
  }
}
