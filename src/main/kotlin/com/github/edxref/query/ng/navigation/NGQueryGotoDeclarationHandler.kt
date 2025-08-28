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
      return null
    }

    val project = sourceElement.project

    // Skip during dumb mode for better performance
    if (DumbService.isDumb(project)) {
      return null
    }

    try {
      val ngQueryService = NGQueryService.getInstance(project)
      val settings = project.getQueryRefSettings()

      // Extract Query ID based on source element type
      var queryId: String? = null
      var originType: OriginType? = null

      // Case A: XML <query id="..."> attribute value
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
        }
      }
      // Case B: Java string literal in @SQLRef annotation
      else if (
        sourceElement is PsiJavaToken && sourceElement.tokenType == JavaTokenType.STRING_LITERAL
      ) {
        val literal = sourceElement.parent as? PsiLiteralExpression
        if (literal != null && literal.value is String) {
          // Check for @SQLRef annotation
          val annotationAttribute = literal.parent as? PsiNameValuePair
          val annotation = annotationAttribute?.parent?.parent as? PsiAnnotation

          if (annotation != null) {
            val annotationText = annotation.text
            val expectedAnnotationName = settings.sqlRefAnnotationFqn.substringAfterLast('.')

            if (annotationText.contains(expectedAnnotationName)) {
              val expectedAttributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }
              if (
                annotationAttribute.name == expectedAttributeName ||
                  annotationAttribute.name == null
              ) {
                queryId = literal.value as String
                originType = OriginType.SQLREF
              }
            }
          }

          // Check for QueryUtils.getQuery("...") usage
          if (queryId == null) {
            val methodCall = literal.parent?.parent as? PsiMethodCallExpression
            if (methodCall != null) {
              val methodExpr = methodCall.methodExpression
              val methodName = methodExpr.referenceName
              val expectedMethodName = settings.queryUtilsMethodName.ifBlank { "getQuery" }

              if (methodName == expectedMethodName) {
                val qualifierText = methodExpr.qualifierExpression?.text?.lowercase()
                if (qualifierText?.contains("queryutils") == true) {
                  queryId = literal.value as String
                  originType = OriginType.QUERY_UTILS
                }
              }
            }
          }
        }
      }

      if (queryId == null || originType == null) {
        return null
      }

      // Find all potential targets using the NGQueryService
      val targets = mutableListOf<PsiElement>()

      when (originType) {
        OriginType.XML -> {
          // From XML, navigate to Java usages
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

      // Filter out the source element itself
      val sourceContainer =
        when (originType) {
          OriginType.XML -> sourceElement.parent?.parent?.parent as? XmlTag
          OriginType.SQLREF -> PsiTreeUtil.getParentOfType(sourceElement, PsiClass::class.java)
          OriginType.QUERY_UTILS -> sourceElement.parent?.parent as? PsiMethodCallExpression
        }

      val finalTargets = targets.filter { target -> target != sourceContainer && target.isValid }

      return if (finalTargets.isNotEmpty()) {
        finalTargets.toTypedArray()
      } else {
        null
      }
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: Exception) {
      log.debug("Error in goto declaration handler", e)
      return null
    }
  }

  private enum class OriginType {
    XML,
    SQLREF,
    QUERY_UTILS,
  }
}
