package com.github.edxref.query.ng.gutter

import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.ng.service.NGQueryService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.*

/** Line marker for @SQLRef annotations with lazy loading */
class NGSQLRefLineMarkerProvider : LineMarkerProvider {

  private val log = logger<NGSQLRefLineMarkerProvider>()

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Skip during indexing
    if (DumbService.isDumb(element.project)) {
      return null
    }

    // Only process leaf elements (PsiIdentifier for annotation name)
    if (element !is PsiIdentifier) return null

    try {
      // Check if this identifier is part of an annotation
      val parent = element.parent
      if (parent !is PsiJavaCodeReferenceElement) return null

      val annotation = parent.parent as? PsiAnnotation ?: return null

      // Check if this is the annotation name identifier
      if (annotation.nameReferenceElement?.referenceNameElement != element) return null

      val qualifiedName = annotation.qualifiedName
      if (qualifiedName?.endsWith("SQLRef") == true) {
        val refId = extractRefIdValue(annotation)
        if (!refId.isNullOrBlank()) {

          // Create lazy target provider with explicit Collection<PsiElement> type
          val lazyTargets: NotNullLazyValue<Collection<PsiElement>> =
            NotNullLazyValue.createValue {
              ApplicationManager.getApplication()
                .runReadAction(
                  Computable<Collection<PsiElement>> {
                    try {
                      val service = NGQueryService.getInstance(element.project)
                      val xmlTag = service.findXmlTagById(refId)
                      if (xmlTag != null) {
                        listOf(xmlTag) as Collection<PsiElement>
                      } else {
                        emptyList<PsiElement>() as Collection<PsiElement>
                      }
                    } catch (e: Exception) {
                      log.debug("Error loading XML tag for refId: $refId", e)
                      emptyList<PsiElement>() as Collection<PsiElement>
                    }
                  }
                )
            }

          return NavigationGutterIconBuilder.create(EDXRefIcons.JAVA_TO_XML)
            .setTargets(lazyTargets)
            .setTooltipText("Navigate to Query XML definition")
            .setAlignment(GutterIconRenderer.Alignment.LEFT)
            .createLineMarkerInfo(element)
        }
      }
    } catch (e: ProcessCanceledException) {
      throw e
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
    try {
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
    } catch (e: Exception) {
      log.debug("Error extracting refId value", e)
    }

    return null
  }
}
