package com.github.edxref.query.ng.gutter

import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.ng.service.NGQueryService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.*

/** Line marker for QueryUtils.getQuery() calls */
class NGQueryUtilsLineMarkerProvider : LineMarkerProvider {

  private val log = logger<NGQueryUtilsLineMarkerProvider>()

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // IMPORTANT: Only process leaf elements (PsiJavaToken) not PsiLiteralExpression
    if (element !is PsiJavaToken) return null

    // Check if this is a string literal token
    if (element.tokenType != JavaTokenType.STRING_LITERAL) return null

    try {
      // Get the parent literal expression
      val literal = element.parent as? PsiLiteralExpression ?: return null
      val literalValue = literal.value as? String ?: return null

      // Check if this is part of a method call
      val methodCall = literal.parent?.parent as? PsiMethodCallExpression ?: return null
      val methodExpr = methodCall.methodExpression

      if (methodExpr.referenceName == "getQuery") {
        val qualifierText = methodExpr.qualifierExpression?.text?.lowercase()
        if (qualifierText?.contains("queryutils") == true) {
          val service = NGQueryService.getInstance(element.project)
          val xmlTag = service.findXmlTagById(literalValue)

          if (xmlTag != null) {
            return NavigationGutterIconBuilder.create(EDXRefIcons.METHOD_JAVA__TO_XML)
              .setTargets(xmlTag)
              .setTooltipText("Navigate to Query XML definition")
              .setAlignment(GutterIconRenderer.Alignment.LEFT)
              .createLineMarkerInfo(element) // element is now a leaf (PsiJavaToken)
          }
        }
      }
    } catch (e: Exception) {
      log.debug("Error processing element", e)
    }

    return null
  }

  override fun collectSlowLineMarkers(
    elements: List<PsiElement>,
    result: MutableCollection<in LineMarkerInfo<*>>,
  ) {
    // Empty - we handle everything in getLineMarkerInfo
  }
}
