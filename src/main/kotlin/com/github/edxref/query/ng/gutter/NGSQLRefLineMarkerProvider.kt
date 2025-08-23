package com.github.edxref.query.ng.gutter

import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.ng.service.NGQueryService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.*

/** Line marker for @SQLRef annotations */
class NGSQLRefLineMarkerProvider : LineMarkerProvider {

  private val log = logger<NGSQLRefLineMarkerProvider>()

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // IMPORTANT: Only process leaf elements (PsiIdentifier for annotation name)
    if (element !is PsiIdentifier) return null

    try {
      // Check if this identifier is part of an annotation
      val parent = element.parent
      if (parent !is PsiJavaCodeReferenceElement) return null

      val annotation = parent.parent as? PsiAnnotation ?: return null

      // Check if this is the annotation name identifier (not an attribute name)
      if (annotation.nameReferenceElement?.referenceNameElement != element) return null

      val qualifiedName = annotation.qualifiedName
      if (qualifiedName?.endsWith("SQLRef") == true) {
        val refId = extractRefIdValue(annotation)
        if (!refId.isNullOrBlank()) {
          val service = NGQueryService.getInstance(element.project)
          val xmlTag = service.findXmlTagById(refId)

          if (xmlTag != null) {
            return NavigationGutterIconBuilder.create(EDXRefIcons.JAVA_TO_XML)
              .setTargets(xmlTag)
              .setTooltipText("Navigate to Query XML definition")
              .setAlignment(GutterIconRenderer.Alignment.LEFT)
              .createLineMarkerInfo(element) // element is now a leaf (PsiIdentifier)
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

  private fun extractRefIdValue(annotation: PsiAnnotation): String? {
    // Try to get refId attribute value
    val refIdAttr = annotation.findAttributeValue("refId")
    if (refIdAttr is PsiLiteralExpression) {
      return refIdAttr.value as? String
    }

    // If it's the default value (single value annotation)
    val defaultValue = annotation.findAttributeValue(null)
    if (defaultValue is PsiLiteralExpression) {
      return defaultValue.value as? String
    }

    return null
  }
}
