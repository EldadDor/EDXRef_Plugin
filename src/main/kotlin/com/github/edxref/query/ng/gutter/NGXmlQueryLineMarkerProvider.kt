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
import com.intellij.psi.xml.*
import java.util.concurrent.ConcurrentHashMap

/** Optimized line marker provider with lazy target computation using NotNullLazyValue */
class NGXmlQueryLineMarkerProvider : LineMarkerProvider {

  companion object {
    private val log = logger<NGXmlQueryLineMarkerProvider>()

    // Cache to avoid repeated computations during the same session
    private val markerCache = ConcurrentHashMap<String, Boolean>()
  }

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Fast path: Skip during indexing
    if (DumbService.isDumb(element.project)) {
      return null
    }

    // Only process the right token type
    if (element !is XmlToken || element.tokenType != XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN) {
      return null
    }

    try {
      val attributeValue = element.parent as? XmlAttributeValue ?: return null
      val attribute = attributeValue.parent as? XmlAttribute ?: return null

      // Quick check without accessing tag
      if (attribute.name != "id") return null

      val tag = attribute.parent as? XmlTag ?: return null
      if (tag.name != "query") return null

      val queryId = attributeValue.value
      if (queryId.isBlank()) return null

      // Check cache first to avoid expensive operations
      val cacheKey = "${element.project.locationHash}_$queryId"
      if (markerCache[cacheKey] == false) {
        return null // We already know there are no targets
      }

      // Create a lazy navigation handler using NotNullLazyValue
      return createLazyLineMarker(element, queryId, cacheKey)
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: Exception) {
      log.debug("Error in getLineMarkerInfo", e)
    }

    return null
  }

  private fun createLazyLineMarker(
    element: PsiElement,
    queryId: String,
    cacheKey: String,
  ): LineMarkerInfo<*> {
    // Create a NotNullLazyValue with explicit Collection<PsiElement> type
    val lazyTargets: NotNullLazyValue<Collection<PsiElement>> =
      NotNullLazyValue.createValue {
        // This block is only executed when the user actually clicks the icon
        ApplicationManager.getApplication()
          .runReadAction(
            Computable<Collection<PsiElement>> {
              val service = NGQueryService.getInstance(element.project)
              val allTargets =
                mutableListOf<PsiElement>() // Use mutableListOf instead of mutableSetOf

              try {
                allTargets.addAll(service.findQueryUtilsUsages(queryId))
                allTargets.addAll(service.findSQLRefAnnotations(queryId))

                val validTargets = allTargets.filter { it.isValid }

                // Update cache
                markerCache[cacheKey] = validTargets.isNotEmpty()

                log.debug("Lazy loaded ${validTargets.size} targets for queryId: $queryId")
                validTargets as Collection<PsiElement> // Explicit cast to ensure type
              } catch (e: Exception) {
                log.debug("Error loading targets for queryId: $queryId", e)
                emptyList<PsiElement>() as Collection<PsiElement>
              }
            }
          )
      }

    val builder =
      NavigationGutterIconBuilder.create(EDXRefIcons.XML_TO_JAVA)
        .setAlignment(GutterIconRenderer.Alignment.LEFT)
        .setTooltipText("Navigate to usages")
        .setTargets(lazyTargets) // This should now work with proper typing

    return builder.createLineMarkerInfo(element)
  }

  override fun collectSlowLineMarkers(
    elements: List<PsiElement>,
    result: MutableCollection<in LineMarkerInfo<*>>,
  ) {
    // Empty - we handle everything in getLineMarkerInfo for better responsiveness
  }
}
