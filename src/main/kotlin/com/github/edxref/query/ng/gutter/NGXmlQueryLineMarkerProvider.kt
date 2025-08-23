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
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.xml.*

class NGXmlQueryLineMarkerProvider : LineMarkerProvider {

  private val log = logger<NGXmlQueryLineMarkerProvider>()

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Only process XML_ATTRIBUTE_VALUE_TOKEN tokens
    if (element !is XmlToken || element.tokenType != XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN) {
      return null
    }

    try {
      val attributeValue = element.parent as? XmlAttributeValue ?: return null
      val attribute = attributeValue.parent as? XmlAttribute ?: return null
      val tag = attribute.parent as? XmlTag ?: return null

      if (attribute.name == "id" && tag.name == "query") {
        val queryId = attributeValue.value
        if (queryId.isNotBlank()) {
          val service = NGQueryService.getInstance(element.project)

          // Use read action for thread safety
          val targets =
            ApplicationManager.getApplication()
              .runReadAction(
                Computable {
                  val allTargets = mutableSetOf<PsiElement>() // Use Set to prevent duplicates

                  // Add QueryUtils usages
                  val queryUtilsUsages = service.findQueryUtilsUsages(queryId)
                  allTargets.addAll(queryUtilsUsages)

                  // Add SQLRef annotations
                  val sqlRefAnnotations = service.findSQLRefAnnotations(queryId)
                  allTargets.addAll(sqlRefAnnotations)

                  // Filter valid and return as list
                  allTargets.filter { it.isValid }.toList()
                }
              )

          if (targets.isNotEmpty()) {
            log.debug(
              "Creating line marker for queryId: $queryId with ${targets.size} unique targets"
            )

            return NavigationGutterIconBuilder.create(EDXRefIcons.XML_TO_JAVA)
              .setTargets(targets)
              .setTooltipText("Navigate to ${targets.size} usage(s)")
              .setAlignment(GutterIconRenderer.Alignment.LEFT)
              .createLineMarkerInfo(element)
          }
        }
      }
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: Exception) {
      log.debug("Error in getLineMarkerInfo", e)
    }

    return null
  }

  override fun collectSlowLineMarkers(
    elements: List<PsiElement>,
    result: MutableCollection<in LineMarkerInfo<*>>,
  ) {
    // Empty - we handle everything in getLineMarkerInfo for better responsiveness
  }
}
