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

/** Line marker for QueryUtils.getQuery() calls with lazy loading */
class NGQueryUtilsLineMarkerProvider : LineMarkerProvider {

  private val log = logger<NGQueryUtilsLineMarkerProvider>()

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Skip during indexing
    if (DumbService.isDumb(element.project)) {
      return null
    }

    // Only process leaf elements (PsiJavaToken) not PsiLiteralExpression
    if (element !is PsiJavaToken || element.tokenType != JavaTokenType.STRING_LITERAL) {
      return null
    }

    try {
      // Get the parent literal expression
      val literal = element.parent as? PsiLiteralExpression ?: return null
      val literalValue = literal.value as? String ?: return null

      // Check if this is part of a method call
      val methodCall = literal.parent?.parent as? PsiMethodCallExpression ?: return null
      val methodExpr = methodCall.methodExpression

      if (methodExpr.referenceName == "getQuery") {
        val qualifierText = methodExpr.qualifierExpression?.text?.lowercase()
        if (qualifierText?.contains("queryutils") == true) {

          // Create lazy target provider with explicit Collection<PsiElement> type
          val lazyTargets: NotNullLazyValue<Collection<PsiElement>> =
            NotNullLazyValue.createValue {
              ApplicationManager.getApplication()
                .runReadAction(
                  Computable<Collection<PsiElement>> {
                    try {
                      val service = NGQueryService.getInstance(element.project)
                      val xmlTag = service.findXmlTagById(literalValue)
                      if (xmlTag != null) {
                        listOf(xmlTag) as Collection<PsiElement>
                      } else {
                        emptyList<PsiElement>() as Collection<PsiElement>
                      }
                    } catch (e: Exception) {
                      log.debug("Error loading XML tag for queryId: $literalValue", e)
                      emptyList<PsiElement>() as Collection<PsiElement>
                    }
                  }
                )
            }

          return NavigationGutterIconBuilder.create(EDXRefIcons.METHOD_JAVA__TO_XML)
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
}
