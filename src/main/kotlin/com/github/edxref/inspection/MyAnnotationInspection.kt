package com.github.edxref.inspection

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class MyAnnotationInspection : AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : KtVisitorVoid() {
        override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
            super.visitAnnotationEntry(annotationEntry)

            // Only process annotations named "ChildAnnotation"
            val annotationShortName = annotationEntry.shortName?.asString() ?: return
            if (annotationShortName != "ChildAnnotation") return

            // Resolve the annotation entry using the classic analysis API.
            val context = annotationEntry.analyze(BodyResolveMode.PARTIAL)
            val annotationDescriptor = context[BindingContext.ANNOTATION, annotationEntry] ?: return

            // Get the annotation class descriptor via its type's constructor.
            val annotationClassDescriptor: DeclarationDescriptor = annotationDescriptor.type.constructor.declarationDescriptor ?: return

            // Create a FqName for the required meta-annotation.
            val requiredMetaAnnotationFqName = FqName("com.example.ParentAnnotation")

            // Check whether the annotation class has the required meta-annotation.
            // Instead of using fqNameSafe, we leverage findAnnotation().
            val hasRequiredAnnotation = annotationClassDescriptor.annotations.findAnnotation(requiredMetaAnnotationFqName) != null

            if (!hasRequiredAnnotation) {
                holder.registerProblem(
                    annotationEntry, "ChildAnnotation must target an annotation class annotated with @${requiredMetaAnnotationFqName.asString()}."
                )
            }
        }
    }
}
