package com.github.edxref.query.gutter

// Import the specific class from the impl package (use with caution)
import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.cache.QueryIndexService2
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType

class XmlQueryLineMarkerProvider : LineMarkerProvider {

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Fast path for XML attribute values
    if (element is XmlAttributeValue) {
      return createLineMarkerFromCache(element)
    }
    return null
  }

  private fun createLineMarkerFromCache(element: XmlAttributeValue): LineMarkerInfo<*>? {
    val project = element.project

    // Check if we're in dumb mode - return null to defer to collectSlowLineMarkers
    if (DumbService.isDumb(project)) {
      return null
    }

    val attribute = element.parent as? XmlAttribute ?: return null
    val tag = attribute.parent as? XmlTag ?: return null

    // Quick check for the right attribute and tag
    if (attribute.name != "id" || tag.name != "query") {
      return null
    }

    val queryId = element.value
    if (queryId.isBlank()) {
      return null
    }

    try {
      val queryIndexService = QueryIndexService2.getInstance(project)
      val targetInterface = queryIndexService.findInterfaceById(queryId)
      val queryUtilsUsages = queryIndexService.findQueryUtilsUsagesById(queryId)

      val targets = mutableListOf<PsiElement>()
      if (targetInterface != null) targets.add(targetInterface)
      targets.addAll(queryUtilsUsages)

      if (targets.isNotEmpty()) {
        val builder =
          NavigationGutterIconBuilder.create(EDXRefIcons.XML_TO_JAVA)
            .setTargets(targets)
            .setTooltipText("Navigate to Query Interface and/or QueryUtils usages")
            .setAlignment(GutterIconRenderer.Alignment.LEFT)

        val valueToken =
          element.children.filterIsInstance<XmlToken>().firstOrNull {
            it.tokenType == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN
          }

        if (valueToken != null) {
          return builder.createLineMarkerInfo(valueToken)
        }
      }
    } catch (e: Exception) {
      // If anything goes wrong in the fast path, fall back to slow path
      return null
    }

    return null
  }

  override fun collectSlowLineMarkers(
    elements: List<PsiElement>,
    result: MutableCollection<in LineMarkerInfo<*>>,
  ) {
    if (elements.isEmpty() || DumbService.isDumb(elements.first().project)) {
      return
    }

    val project = elements.first().project
    val queryIndexService = QueryIndexService2.getInstance(project)

    // Collect all query IDs from XML elements
    val queryIds = mutableSetOf<String>()
    val elementToQueryId = mutableMapOf<PsiElement, String>()

    for (element in elements) {
      if (element is XmlAttributeValue) {
        val attribute = element.parent as? XmlAttribute
        val tag = attribute?.parent as? XmlTag

        if (attribute?.name == "id" && tag?.name == "query") {
          val queryId = element.value
          if (queryId.isNotBlank()) {
            queryIds.add(queryId)
            elementToQueryId[element] = queryId
          }
        }
      }
    }

    if (queryIds.isEmpty()) return

    // Batch lookup for SQLRef annotations and QueryUtils usages
    val sqlRefAnnotations = queryIndexService.findMultipleInterfacesById(queryIds)
    val queryUtilsUsages = queryIndexService.findMultipleQueryUtilsUsagesById(queryIds)

    // Create line markers for found elements
    elementToQueryId.forEach { (element, queryId) ->
      val targets = mutableListOf<PsiElement>()

      sqlRefAnnotations[queryId]?.let { targets.add(it) }
      queryUtilsUsages[queryId]?.let { targets.addAll(it) }

      if (targets.isNotEmpty()) {
        val builder =
          NavigationGutterIconBuilder.create(EDXRefIcons.XML_TO_JAVA)
            .setTargets(targets)
            .setTooltipText("Navigate to SQLRef annotations and QueryUtils usages")
            .setAlignment(GutterIconRenderer.Alignment.LEFT)

        val valueToken =
          (element as XmlAttributeValue).children.filterIsInstance<XmlToken>().firstOrNull {
            it.tokenType == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN
          }

        if (valueToken != null) {
          result.add(builder.createLineMarkerInfo(valueToken))
        }
      }
    }
  }
}
