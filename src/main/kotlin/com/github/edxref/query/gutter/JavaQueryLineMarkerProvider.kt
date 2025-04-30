package com.github.edxref.query.gutter

import com.github.edxref.icons.EDXRefIcons // Import custom icons
import com.github.edxref.query.util.QueryIdResolver
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder // Import Builder
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier // Import PsiIdentifier

class JavaQueryLineMarkerProvider : LineMarkerProvider {

    // Implement the required getLineMarkerInfo method
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Target the class name identifier for better positioning
        if (element is PsiIdentifier && element.parent is PsiClass) {
            val psiClass = element.parent as PsiClass
            if (psiClass.isInterface) { // Ensure it's an interface
                val ann = psiClass.annotations.firstOrNull { it.qualifiedName == "SQLRef" }
                val refIdLiteral = ann?.findAttributeValue("refId")
                val refId = refIdLiteral?.text?.replace("\"", "")

                if (refId != null) {
                    // Resolve the target directly here
                    val targetXmlTag = QueryIdResolver.resolveQueryXml(refId, element.project)

                    // If a target is found, create the marker using the builder
                    if (targetXmlTag != null) {
                        val builder = NavigationGutterIconBuilder
                            .create(EDXRefIcons.JAVA_TO_XML) // Use custom icon
                            // Pass the single target directly (uses vararg overload)
                            .setTargets(targetXmlTag)
                            .setTooltipText("Navigate to SQL Query XML definition")
                            .setAlignment(GutterIconRenderer.Alignment.LEFT)
                            // Set the anchor element for the icon (the class name identifier)
                            .setTargetRenderer { element as PsiTargetPresentationRenderer<PsiElement>? } // Optional: Customize how target is shown in popup

                        // Create the marker anchored to the PsiIdentifier (element)
                        return builder.createLineMarkerInfo(element)
                    }
                }
            }
        }
        // Return null if no marker should be created for this element
        return null
    }

    // No need for collectSlowLineMarkers unless you want batch processing
}
