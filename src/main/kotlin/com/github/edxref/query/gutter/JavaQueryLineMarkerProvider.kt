package com.github.edxref.query.gutter

import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.cache.QueryIndexService
import com.github.edxref.query.settings.QueryRefSettings
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
        // Handled by collectSlowLineMarkers
        return null
    }

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        if (elements.isEmpty() || DumbService.isDumb(elements.first().project)) {
            log.debug("Skipping Java markers: No elements or in dumb mode.")
            return
        }

        val project = elements.first().project
        val settings = project.getQueryRefSettings().state
        val annotationFqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" } // Use default if blank
        val attributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" } // Use default if blank
        val queryIndexService = QueryIndexService.getInstance(project)

        log.debug("Checking ${elements.size} elements for Java line markers. Annotation: $annotationFqn, Attribute: $attributeName")

        for (element in elements) {
            // Only process PsiIdentifier elements
            if (element is PsiIdentifier) {
                // Check if the identifier is part of an annotation
                val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java)
                if (annotation != null && annotation.qualifiedName == annotationFqn) {
                    // Ensure we're attaching the marker to the annotation name (SQLRef), not the attribute name (refId)
                    if (annotation.nameReferenceElement?.referenceName != element.text) {
                        log.debug("Skipping identifier '${element.text}' as it is not the annotation name.")
                        continue
                    }

                    log.debug("Found annotation '${annotation.text}' attached to identifier '${element.text}'")

                    // Extract the query ID from the specified attribute
                    val queryId = annotation.findAttributeValue(attributeName)?.text?.replace("\"", "")

                    if (queryId != null && queryId.isNotBlank()) {
                        log.debug("Extracted queryId '$queryId' from annotation.")

                        // Attempt to find the corresponding XML tag using the cache service
                        val targetXmlTag: PsiElement? = queryIndexService.findXmlTagById(queryId)

                        if (targetXmlTag != null) {
                            log.debug("Successfully found target XML tag for queryId '$queryId': ${targetXmlTag.text}")
                            // Build the marker
                            val builder = NavigationGutterIconBuilder
                                .create(EDXRefIcons.JAVA_TO_XML) // Use appropriate icon
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
                        log.debug("Could not extract valid queryId from attribute '$attributeName' in annotation '${annotation.text}'.")
                    }
                }
            }
        }
    }
}
