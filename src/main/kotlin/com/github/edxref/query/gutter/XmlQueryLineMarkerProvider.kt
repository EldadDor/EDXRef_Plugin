package com.github.edxref.query.gutter;
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
// Import the specific class from the impl package (use with caution)
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.platform.backend.presentation.TargetPresentation // Import TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiClass // Import PsiClass
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.github.edxref.icons.EDXRefIcons
import com.github.edxref.query.util.QueryIdResolver

class XmlQueryLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return null
    }

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        for (element in elements) {
            if (element is XmlAttributeValue) {
                val attribute = element.parent as? XmlAttribute
                val tag = attribute?.parent as? XmlTag

                if (attribute?.name == "id" && tag?.name == "query") {
                    val queryId = element.value
                    if (queryId.isNotBlank()) {
                        val targetInterface: PsiElement? = QueryIdResolver.resolveQueryInterface(queryId, element.project)

                        if (targetInterface != null) {
                            val builder = NavigationGutterIconBuilder
                                .create(EDXRefIcons.XML_TO_JAVA)
                                .setTargets(targetInterface)
                                .setTooltipText("Navigate to Query Interface definition")
                                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                                // Provide a Supplier lambda: () -> PsiTargetPresentationRenderer
                                .setTargetRenderer {
                                    // This lambda takes NO arguments.
                                    // Return an instance of an anonymous class EXTENDING PsiTargetPresentationRenderer
                                    object : PsiTargetPresentationRenderer<PsiElement>() { // Note the () for class extension
                                        // Override the modern presentation method
                                        override fun getPresentation(element: PsiElement): TargetPresentation {
                                            // Determine the text for the target element
                                            val text = (element as? PsiClass)?.name
                                                ?: element.text
                                                ?: "Target Interface"

                                            // Build and return the TargetPresentation
                                            // You can customize icon, location text etc. here too if needed
                                            return TargetPresentation.builder(text).presentation()
                                        }

                                        // Fallback/alternative: Override older methods if needed,
                                        // but getPresentation is likely sufficient.
                                        // override fun getElementText(element: PsiElement): String {
                                        //     return (element as? PsiClass)?.name ?: element.text ?: "Target Interface"
                                        // }
                                    }
                                }

                            val markerInfo = builder.createLineMarkerInfo(element)
                            result.add(markerInfo)
                        }
                    }
                }
            }
        }
    }
}
