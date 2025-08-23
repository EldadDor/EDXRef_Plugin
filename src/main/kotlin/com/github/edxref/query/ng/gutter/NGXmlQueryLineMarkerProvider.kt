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
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken

/** Line marker for XML query definitions - now includes SQLRef annotations */
class NGXmlQueryLineMarkerProvider : LineMarkerProvider {

  private val log = logger<NGXmlQueryLineMarkerProvider>()

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Fast path for simple cases
    if (element !is XmlToken) return null

    try {
      val attributeValue = element.parent as? XmlAttributeValue ?: return null
      val attribute = attributeValue.parent as? XmlAttribute ?: return null
      val tag = attribute.parent as? XmlTag ?: return null

      if (attribute.name == "id" && tag.name == "query") {
        val queryId = attributeValue.value
        if (queryId.isNotBlank()) {
          // Quick check - if we have cached results, show immediately
          val service = NGQueryService.getInstance(element.project)

          // Use read action for thread safety
          val targets =
            ApplicationManager.getApplication()
              .runReadAction(
                Computable {
                  val allTargets = mutableListOf<PsiElement>()
                  allTargets.addAll(service.findQueryUtilsUsages(queryId))
                  allTargets.addAll(service.findSQLRefAnnotations(queryId))
                  allTargets.filter { it.isValid }
                }
              )

          if (targets.isNotEmpty()) {
            return NavigationGutterIconBuilder.create(EDXRefIcons.XML_TO_JAVA)
              .setTargets(targets)
              .setTooltipText("Navigate to QueryUtils usages and SQLRef annotations")
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
    // We're handling everything in getLineMarkerInfo for better responsiveness
    // This method can be used for batch operations if needed
  }
}
