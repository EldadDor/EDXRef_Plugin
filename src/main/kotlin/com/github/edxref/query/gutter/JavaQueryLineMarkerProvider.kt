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
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

class JavaQueryLineMarkerProvider : LineMarkerProvider {

  private val log = logger<JavaQueryLineMarkerProvider>()

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Fast path for common cases
    if (element is PsiIdentifier) {
      val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java)
      if (annotation != null) {
        return createLineMarkerFromCache(element, annotation)
      }
    }
    return null
  }

  private fun createLineMarkerFromCache(
    element: PsiIdentifier,
    annotation: PsiAnnotation,
  ): LineMarkerInfo<*>? {
    val project = element.project

    // Check if we're in dumb mode - return null to defer to collectSlowLineMarkers
    if (DumbService.isDumb(project)) {
      return null
    }

    val settings = project.getQueryRefSettings().state
    val annotationFqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }
    val attributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }

    // Quick check if this is the annotation we care about
    if (annotation.qualifiedName != annotationFqn) {
      return null
    }

    // Ensure we're attaching the marker to the annotation name, not the attribute name
    if (annotation.nameReferenceElement?.referenceName != element.text) {
      return null
    }

    // Extract the query ID from the specified attribute
    val queryId = annotation.findAttributeValue(attributeName)?.text?.replace("\"", "")
    if (queryId.isNullOrBlank()) {
      return null
    }

    try {
      // Attempt to find the corresponding XML tag using the cache service
      val queryIndexService = QueryIndexService.getInstance(project)
      val targetXmlTag = queryIndexService.findXmlTagById(queryId)

      if (targetXmlTag != null) {
        log.debug("Fast path: Found target XML tag for queryId '$queryId'")

        // Build the marker
        val builder =
          NavigationGutterIconBuilder.create(EDXRefIcons.JAVA_TO_XML)
            .setTargets(targetXmlTag)
            .setTooltipText("Navigate to Query XML definition")
            .setAlignment(GutterIconRenderer.Alignment.LEFT)

        return builder.createLineMarkerInfo(element)
      }
    } catch (e: Exception) {
      // If anything goes wrong in the fast path, fall back to slow path
      log.debug("Fast path failed for queryId '$queryId', will use slow path: ${e.message}")
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

    val project = elements.first().project
    val settings = project.getQueryRefSettings().state
    val annotationFqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }
    val attributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }
    val queryIndexService = QueryIndexService.getInstance(project)

    log.debug(
      "Checking ${elements.size} elements for Java line markers. Annotation: $annotationFqn, Attribute: $attributeName"
    )

    for (element in elements) {
      // Only process PsiIdentifier elements that don't already have markers from fast path
      if (element is PsiIdentifier) {
        // Check if the identifier is part of an annotation
        val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java)
        if (annotation != null && annotation.qualifiedName == annotationFqn) {
          // Ensure we're attaching the marker to the annotation name (SQLRef), not the attribute
          // name (refId)
          if (annotation.nameReferenceElement?.referenceName != element.text) {
            log.debug("Skipping identifier '${element.text}' as it is not the annotation name.")
            continue
          }

          log.debug(
            "Found annotation '${annotation.text}' attached to identifier '${element.text}'"
          )

          // Extract the query ID from the specified attribute
          val queryId = annotation.findAttributeValue(attributeName)?.text?.replace("\"", "")

          if (queryId != null && queryId.isNotBlank()) {
            log.debug("Extracted queryId '$queryId' from annotation.")

            // Attempt to find the corresponding XML tag using the cache service
            val targetXmlTag: PsiElement? = queryIndexService.findXmlTagById(queryId)

            if (targetXmlTag != null) {
              log.debug(
                "Successfully found target XML tag for queryId '$queryId': ${targetXmlTag.text}"
              )
              // Build the marker
              val builder =
                NavigationGutterIconBuilder.create(EDXRefIcons.JAVA_TO_XML)
                  .setTargets(targetXmlTag)
                  .setTooltipText("Navigate to Query XML definition")
                  .setAlignment(GutterIconRenderer.Alignment.LEFT)

              // Create marker info, attached to the annotation name (SQLRef)
              val markerInfo = builder.createLineMarkerInfo(element)
              result.add(markerInfo)
              log.debug("Added line marker for queryId '$queryId'")
            } else {
              // Log why the marker wasn't created
              log.debug("Could not find target XML tag for queryId '$queryId'. No marker added.")
            }
          } else {
            log.debug(
              "Could not extract valid queryId from attribute '$attributeName' in annotation '${annotation.text}'."
            )
          }
        }
      }
    }
  }
}
