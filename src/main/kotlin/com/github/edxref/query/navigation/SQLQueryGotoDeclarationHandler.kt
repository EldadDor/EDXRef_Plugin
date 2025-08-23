package com.github.edxref.query.navigation

import com.github.edxref.query.cache.QueryIndexService
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.*
import com.intellij.psi.JavaTokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType

class QueryGotoDeclarationHandler : GotoDeclarationHandler {

  private val log = logger<QueryGotoDeclarationHandler>()

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
    if (DumbService.isDumb(project)) {
      log.debug("Project is in dumb mode.")
      return null // Cannot use index service during dumb mode
    }
    val queryIndexService = QueryIndexService.getInstance(project)
    val settings = project.getQueryRefSettings()

    // --- 1. Extract Query ID based on source element type ---
    var queryId: String? = null
    var originType: OriginType? = null // To help filter out the source later

    // Case A: Clicked inside XML <query id="..."> attribute value
    if (
      sourceElement is XmlToken && sourceElement.tokenType == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN
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
    // Case B/C: Clicked inside a Java string literal (in annotation or method call)
    else if (
      sourceElement is PsiJavaToken && sourceElement.tokenType == JavaTokenType.STRING_LITERAL
    ) {
      val literal = sourceElement.parent as? PsiLiteralExpression
      if (literal != null && literal.value is String) {
        // Check for @SQLRef(refId="...") annotation usage
        val annotationAttribute = literal.parent as? PsiNameValuePair
        val annotation = annotationAttribute?.parent?.parent as? PsiAnnotation
        if (
          annotation != null &&
            annotation.qualifiedName == settings.sqlRefAnnotationFqn &&
            annotationAttribute.name == settings.sqlRefAnnotationAttributeName
        ) {
          queryId = literal.value as String
          originType = OriginType.SQLREF
          log.debug("Origin: @SQLRef literal value. QueryId: $queryId")
        } else {
          // Check for QueryUtils.getQuery("...") usage
          val methodCall = literal.parent?.parent as? PsiMethodCallExpression
          if (methodCall != null) {
            val methodExpr = methodCall.methodExpression
            val methodName = methodExpr.referenceName
            val qualifierExpr = methodExpr.qualifierExpression as? PsiReferenceExpression
            val qualifierType = qualifierExpr?.type
            val qualifierFqn = qualifierType?.canonicalText

            if (
              methodName == settings.queryUtilsMethodName && qualifierFqn == settings.queryUtilsFqn
            ) {
              queryId = literal.value as String
              originType = OriginType.QUERY_UTILS
              log.debug("Origin: QueryUtils literal value. QueryId: $queryId")
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

    // --- 2. Find all potential targets using the QueryIndexService ---
    log.debug("Searching for targets for queryId: $queryId")
    val targets = mutableListOf<PsiElement>()

    queryIndexService.findXmlTagById(queryId)?.let { targets.add(it) }
    queryIndexService.findInterfaceById(queryId)?.let { targets.add(it) }
    targets.addAll(queryIndexService.findQueryUtilsUsagesById(queryId))

    // --- 3. Filter out the source element itself ---
    val finalTargets =
      targets.filter { target ->
        when (originType) {
          // If origin is XML, don't navigate to the same XML tag
          OriginType.XML -> target != (sourceElement.parent?.parent?.parent as? XmlTag)
          // If origin is @SQLRef literal, don't navigate to the containing class/interface
          OriginType.SQLREF ->
            target != PsiTreeUtil.getParentOfType(sourceElement, PsiClass::class.java)
          // If origin is QueryUtils literal, don't navigate to the exact same literal expression
          OriginType.QUERY_UTILS -> {
            // target should not be the same literal expression
            val literal = (sourceElement.parent as? PsiLiteralExpression)
            target != literal
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
  }

  // Helper enum to track where the navigation started
  private enum class OriginType {
    XML,
    SQLREF,
    QUERY_UTILS,
  }
}
