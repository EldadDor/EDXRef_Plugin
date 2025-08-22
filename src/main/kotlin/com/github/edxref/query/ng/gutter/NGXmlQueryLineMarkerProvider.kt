package com.github.edxref.query.ng.gutter

import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.ng.service.NGQueryService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.*

/** Line marker for XML query definitions - now includes SQLRef annotations */
class NGXmlQueryLineMarkerProvider : LineMarkerProvider {

  private val log = logger<NGXmlQueryLineMarkerProvider>()

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
        if (element is com.intellij.psi.xml.XmlAttributeValue) {
          val attribute = element.parent as? com.intellij.psi.xml.XmlAttribute ?: continue
          val tag = attribute.parent as? com.intellij.psi.xml.XmlTag ?: continue

          if (attribute.name == "id" && tag.name == "query") {
            val queryId = element.value
            if (queryId.isNotBlank()) {
              // Find both QueryUtils usages and SQLRef annotations
              val targets = mutableListOf<PsiElement>()

              // Add QueryUtils usages
              targets.addAll(service.findQueryUtilsUsages(queryId))

              // Add SQLRef annotations
              targets.addAll(service.findSQLRefAnnotations(queryId))

              if (targets.isNotEmpty()) {
                val builder =
                  NavigationGutterIconBuilder.create(EDXRefIcons.XML_TO_JAVA)
                    .setTargets(targets)
                    .setTooltipText("Navigate to QueryUtils usages and SQLRef annotations")
                    .setAlignment(GutterIconRenderer.Alignment.LEFT)

                val token = element.children.firstOrNull { it is com.intellij.psi.xml.XmlToken }
                if (token != null) {
                  result.add(builder.createLineMarkerInfo(token))
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
}
