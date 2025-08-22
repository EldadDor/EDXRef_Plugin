package com.github.edxref.query.ng.gutter

import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.ng.service.NGQueryService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/** Line marker for @SQLRef annotations */
class NGSQLRefLineMarkerProvider : LineMarkerProvider {

  private val log = logger<NGSQLRefLineMarkerProvider>()

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // We'll use collectSlowLineMarkers for everything
    return null
  }

  override fun collectSlowLineMarkers(
    elements: List<PsiElement>,
    result: MutableCollection<in LineMarkerInfo<*>>,
  ) {
    if (elements.isEmpty()) return

    val service = NGQueryService.getInstance(elements.first().project)

    for (element in elements) {
      try {
        // Look for annotation identifiers
        if (element is PsiIdentifier) {
          val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java)
          if (annotation != null) {
            val qualifiedName = annotation.qualifiedName
            if (qualifiedName?.endsWith("SQLRef") == true) {
              // Make sure we're on the annotation name, not an attribute
              if (annotation.nameReferenceElement?.referenceName == element.text) {
                val refId = extractRefIdValue(annotation)
                if (!refId.isNullOrBlank()) {
                  val xmlTag = service.findXmlTagById(refId)

                  if (xmlTag != null) {
                    val builder =
                      NavigationGutterIconBuilder.create(EDXRefIcons.JAVA_TO_XML)
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
      } catch (e: Exception) {
        log.debug("Error processing element", e)
      }
    }
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
