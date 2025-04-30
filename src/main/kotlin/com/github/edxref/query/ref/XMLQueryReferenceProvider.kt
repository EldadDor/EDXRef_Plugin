package com.github.edxref.query.ref // Adjust import if needed

import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.util.ProcessingContext
import com.github.edxref.query.util.QueryIdResolver // Adjust import if needed
import com.intellij.psi.PsiReferenceBase // Import PsiReferenceBase

class XMLQueryReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {

        // Target the XmlAttributeValue inside the 'id' attribute
        if (element.parent is XmlAttribute) {
            val attribute = element.parent as XmlAttribute
            if (attribute.name == "id" && attribute.parent?.name == "query") {
                val value = attribute.value // value is String?

                // Only create a reference if the attribute value is not null or empty
                if (!value.isNullOrBlank()) {
                    return arrayOf(object : PsiReferenceBase<PsiElement>(element, true) { // Reference the value element
                        override fun resolve(): PsiElement? {
                            // 'value' is smart-cast to non-null String here
                            return QueryIdResolver.resolveQueryInterface(value, element.project)
                        }

                        // Optional: Define range within the attribute value string
                        override fun getRangeInElement(): com.intellij.openapi.util.TextRange {
                            // Range relative to the start of the PsiElement 'element' (the attribute value)
                            // Usually starts after the opening quote and ends before the closing quote.
                            // For simplicity, using the whole element range often works.
                            // If element is XmlAttributeValue, its range is just the value part.
                            return com.intellij.openapi.util.TextRange(0, element.textLength)
                        }

                        // Optional: Handle rename refactoring
                        // override fun handleElementRename(newElementName: String): PsiElement { ... }

                        // Optional: Variants for completion
                        // override fun getVariants(): Array<Any> { ... }
                    })
                }
            }
        }
        return PsiReference.EMPTY_ARRAY
    }
}
