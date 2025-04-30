package com.github.edxref.query.ref

import com.github.edxref.query.util.QueryIdResolver
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiReferenceBase
import com.intellij.util.ProcessingContext

class SQLRefAnnotationReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        if (element is PsiAnnotation && element.qualifiedName == "SQLRef") {
            val refId = element.findAttributeValue("refId")?.text?.replace("\"", "") ?: return PsiReference.EMPTY_ARRAY
            return arrayOf(object : PsiReferenceBase<PsiAnnotation>(element, true) {
                override fun resolve() = QueryIdResolver.resolveQueryXml(refId, element.project)
            })
        }
        return PsiReference.EMPTY_ARRAY
    }
}
