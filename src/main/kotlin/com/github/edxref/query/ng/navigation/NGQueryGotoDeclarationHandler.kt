package com.github.edxref.query.ng.navigation

import com.github.edxref.query.ng.service.NGQueryService
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType

class NGQueryGotoDeclarationHandler : GotoDeclarationHandler {

  companion object {
    private val log = logger<NGQueryGotoDeclarationHandler>()
  }

  override fun getGotoDeclarationTargets(
    sourceElement: PsiElement?,
    offset: Int,
    editor: Editor?,
  ): Array<PsiElement>? {
    if (sourceElement == null) {
      log.debug("Source element is null.")
      return null
    }

    val project = sourceElement.project

    // NG design handles dumb mode internally, but we can still check for early exit
    if (DumbService.isDumb(project)) {
      log.debug("Project is in dumb mode - service will use runtime search.")
    }

    try {
      val ngQueryService = NGQueryService.getInstance(project)
      val settings = project.getQueryRefSettings()

      // --- 1. Extract Query ID based on source element type ---
      var queryId: String? = null
      var originType: OriginType? = null

      // Case A: Clicked inside XML <query id="..."> attribute value
      if (
        sourceElement is XmlToken &&
          sourceElement.tokenType == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN
      ) {
        val attributeValue = sourceElement.parent as? XmlAttributeValue
        val attribute = attributeValue?.parent as? XmlAttribute
        val tag = attribute?.parent as? XmlTag
        if (attribute?.name == "id" && tag?.name == "query") {
          queryId = attributeValue.value
          originType = OriginType.XML
          log.debug("Origin: XML id attribute value. QueryId: $queryId")
        }
      }
      // Case B: Clicked inside a Java string literal in @SQLRef annotation
      else if (
        sourceElement is PsiJavaToken && sourceElement.tokenType == JavaTokenType.STRING_LITERAL
      ) {
        val literal = sourceElement.parent as? PsiLiteralExpression
        if (literal != null && literal.value is String) {
          // Check for @SQLRef(refId="...") annotation usage
          val annotationAttribute = literal.parent as? PsiNameValuePair
          val annotation = annotationAttribute?.parent?.parent as? PsiAnnotation

          if (annotation != null) {
            // Check if this is an SQLRef annotation (using text check to avoid resolution)
            val annotationText = annotation.text
            val expectedAnnotationName = settings.sqlRefAnnotationFqn.substringAfterLast('.')

            if (annotationText.contains(expectedAnnotationName)) {
              // Check attribute name
              val expectedAttributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }
              if (
                annotationAttribute.name == expectedAttributeName ||
                  annotationAttribute.name == null
              ) {
                queryId = literal.value as String
                originType = OriginType.SQLREF
                log.debug("Origin: @SQLRef literal value. QueryId: $queryId")
              }
            }
          }

          // If not an annotation, check for QueryUtils.getQuery("...") usage
          if (queryId == null) {
            val methodCall = literal.parent?.parent as? PsiMethodCallExpression
            if (methodCall != null) {
              val methodExpr = methodCall.methodExpression
              val methodName = methodExpr.referenceName
              val expectedMethodName = settings.queryUtilsMethodName.ifBlank { "getQuery" }

              if (methodName == expectedMethodName) {
                // Check qualifier (simplified check for NG design)
                val qualifierText = methodExpr.qualifierExpression?.text?.lowercase()
                if (qualifierText?.contains("queryutils") == true) {
                  queryId = literal.value as String
                  originType = OriginType.QUERY_UTILS
                  log.debug("Origin: QueryUtils literal value. QueryId: $queryId")
                }
              }
            }
          }
        }
      }

      // If no relevant query ID was found at the source element, stop.
      if (queryId == null || originType == null) {
        log.debug(
          "No relevant query ID found at source element: ${sourceElement.text} (Type: ${sourceElement.javaClass.simpleName})"
        )
        return null
      }

      // --- 2. Find all potential targets using the NGQueryService ---
      log.debug("Searching for targets for queryId: $queryId")
      val targets = mutableListOf<PsiElement>()

      // NG service methods return single element or list
      when (originType) {
        OriginType.XML -> {
          // From XML, navigate to Java usages (QueryUtils and SQLRef)
          targets.addAll(ngQueryService.findQueryUtilsUsages(queryId))
          targets.addAll(ngQueryService.findSQLRefAnnotations(queryId))
        }
        OriginType.SQLREF -> {
          // From SQLRef, navigate to XML and QueryUtils usages
          ngQueryService.findXmlTagById(queryId)?.let { targets.add(it) }
          targets.addAll(ngQueryService.findQueryUtilsUsages(queryId))
        }
        OriginType.QUERY_UTILS -> {
          // From QueryUtils, navigate to XML and SQLRef annotations
          ngQueryService.findXmlTagById(queryId)?.let { targets.add(it) }
          targets.addAll(ngQueryService.findSQLRefAnnotations(queryId))
        }
      }

      // --- 3. Filter out the source element itself ---
      val sourceContainer =
        when (originType) {
          OriginType.XML -> sourceElement.parent?.parent?.parent as? XmlTag
          OriginType.SQLREF -> PsiTreeUtil.getParentOfType(sourceElement, PsiClass::class.java)
          OriginType.QUERY_UTILS -> sourceElement.parent?.parent as? PsiMethodCallExpression
        }

      val finalTargets =
        targets.filter { target ->
          when (originType) {
            OriginType.XML -> {
              // Don't navigate to the same XML tag
              target != sourceContainer
            }
            OriginType.SQLREF -> {
              // Don't navigate to the containing class/interface
              target != sourceContainer
            }
            OriginType.QUERY_UTILS -> {
              // Don't navigate to the exact same method call
              target != sourceContainer
            }
          }
        }

      log.debug("Found ${finalTargets.size} potential navigation targets for '$queryId'.")

      // Return targets if any found, otherwise null
      return if (finalTargets.isNotEmpty()) {
        finalTargets.toTypedArray()
      } else {
        null
      }
    } catch (e: ProcessCanceledException) {
      // Rethrow without logging
      throw e
    } catch (e: Exception) {
      log.debug("Error in goto declaration handler", e)
      return null
    }
  }

  // Helper enum to track where the navigation started
  private enum class OriginType {
    XML,
    SQLREF,
    QUERY_UTILS,
  }
}
