package com.github.edxref.query.ng.gutter

/*
 * User: eadno1
 * Date: 21/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */

import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.ng.service.NGQueryService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.*

/** Line marker for XML query definitions */
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
              val usages = service.findQueryUtilsUsages(queryId)
              if (usages.isNotEmpty()) {
                val builder =
                  NavigationGutterIconBuilder.create(EDXRefIcons.XML_TO_JAVA)
                    .setTargets(usages)
                    .setTooltipText("Navigate to QueryUtils usages")
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
