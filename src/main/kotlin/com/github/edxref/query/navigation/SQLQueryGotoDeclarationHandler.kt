package com.github.edxref.query.navigation // Adjust import if needed

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.psi.PsiElement
import com.intellij.openapi.editor.Editor
import com.github.edxref.query.util.QueryIdResolver // Adjust import if needed
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.PsiAnnotation

class SQLQueryGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {

        if (sourceElement == null) return null

        // Handle navigation from XML attribute (id="...")
        if (sourceElement.parent is XmlAttribute) {
            val attribute = sourceElement.parent as XmlAttribute
            if (attribute.name == "id" && attribute.parent?.name == "query") {
                // Use ?.let to safely handle nullable attribute.value
                attribute.value?.let { queryId ->
                    // 'queryId' is now guaranteed non-null (String)
                    QueryIdResolver.resolveQueryInterface(queryId, attribute.project)?.let { resolvedInterface ->
                        return arrayOf(resolvedInterface) // Return the resolved interface
                    }
                }
            }
        }

        // Handle navigation from Java annotation (@SQLRef(refId="..."))
        // Check if the sourceElement itself or its parent is the annotation value we care about
        var annotationElement = sourceElement
        if (sourceElement.parent is PsiAnnotation) {
            annotationElement = sourceElement.parent
        }

        if (annotationElement is PsiAnnotation && annotationElement.qualifiedName == "SQLRef") { // Adjust annotation name if needed
            // Extract refId safely, handling potential nulls
            val refIdLiteral = annotationElement.findAttributeValue("refId")
            val refId = refIdLiteral?.text?.replace("\"", "") // Get text and remove quotes

            if (refId != null) {
                // refId is non-null here
                QueryIdResolver.resolveQueryXml(refId, annotationElement.project)?.let { resolvedXmlTag ->
                    return arrayOf(resolvedXmlTag) // Return the resolved XML tag
                }
            }
        }

        return null // No target found
    }
}
