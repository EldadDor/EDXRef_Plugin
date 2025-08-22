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

/** Line marker for QueryUtils.getQuery() calls */
class NGQueryUtilsLineMarkerProvider : LineMarkerProvider {

  private val log = logger<NGQueryUtilsLineMarkerProvider>()

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
        if (element is PsiLiteralExpression && element.value is String) {
          val methodCall = element.parent?.parent as? PsiMethodCallExpression ?: continue
          val methodExpr = methodCall.methodExpression

          if (methodExpr.referenceName == "getQuery") {
            val qualifierText = methodExpr.qualifierExpression?.text?.lowercase()
            if (qualifierText?.contains("queryutils") == true) {
              val queryId = element.value as String
              val xmlTag = service.findXmlTagById(queryId)

              if (xmlTag != null) {
                val builder =
                  NavigationGutterIconBuilder.create(EDXRefIcons.METHOD_JAVA__TO_XML)
                    .setTargets(xmlTag)
                    .setTooltipText("Navigate to Query XML definition")
                    .setAlignment(GutterIconRenderer.Alignment.LEFT)

                result.add(builder.createLineMarkerInfo(element))
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
