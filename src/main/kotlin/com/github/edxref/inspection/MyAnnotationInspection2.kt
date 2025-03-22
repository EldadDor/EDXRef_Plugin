package com.github.edxref.inspection


import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

class MyAnnotationInspection2 : AbstractKotlinInspection() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {

            override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
                super.visitAnnotationEntry(annotationEntry)

                // Process only annotations with the short name "WSConsumer"
                val shortName = annotationEntry.shortName?.asString() ?: return
                if (shortName != "WSConsumer") return

                // Locate the containing class (the annotated type).
                val ktClass: KtClass = annotationEntry.getStrictParentOfType() ?: return
                val consumerClassName = ktClass.name ?: return

                // Helper to extract the literal text for a named attribute.
                fun getArgumentText(name: String): String {
                    val arg = annotationEntry.valueArguments.find {
                        it.getArgumentName()?.asName?.asString() == name
                    }
                    // Remove surrounding quotes if any.
                    return arg?.getArgumentExpression()?.text?.trim('"') ?: ""
                }

                val urlValue = getArgumentText("url")
                val pathValue = getArgumentText("path")
                // When not provided, sslCertificateValidation defaults to true.
                val sslText = getArgumentText("sslCertificateValidation")
                val sslCertificateValidation = if (sslText.isEmpty()) true else sslText.toBoolean()

                // Determine if the child annotation msConsumer is explicitly provided.
                val msConsumerArg = annotationEntry.valueArguments.find {
                    it.getArgumentName()?.asName?.asString() == "msConsumer"
                }

                // If msConsumer (child annotation) is present, additional rules apply.
                if (msConsumerArg != null) {
                    // Rule 1: Parent must not supply a url when using msConsumer; use path only.
                    if (urlValue.isNotEmpty()) {
                        holder.registerProblem(
                            annotationEntry,
                            "For @WSConsumer with msConsumer, 'url' must not be specified; use 'path' only."
                        )
                    }

                    // Rule 2: The 'path' should not contain protocol information (http/https).
                    if (pathValue.contains("http://") || pathValue.contains("https://")) {
                        holder.registerProblem(
                            annotationEntry,
                            "The 'path' attribute must not contain http/https; specify only a relative path."
                        )
                    }

                    // Further restrictions if used in PearlWebServiceConsumer.
                    if (consumerClassName == "PearlWebServiceConsumer") {
                        // Retrieve the msConsumer annotation text.
                        // When no explicit 'value' is provided in @WSMsConsumer,
                        // its text will likely not contain the word "value".
                        val msConsumerText = msConsumerArg.getArgumentExpression()?.text ?: ""

                        // If a value is provided, it must be LOCAL.
                        if (msConsumerText.contains("value")) {
                            if (!msConsumerText.contains("LbMsType.LOCAL")) {
                                holder.registerProblem(
                                    annotationEntry,
                                    "When used in PearlWebServiceConsumer, msConsumer value may only be omitted or set to LOCAL."
                                )
                            } else {
                                if (sslCertificateValidation) {
                                    holder.registerProblem(
                                        annotationEntry,
                                        "For PearlWebServiceConsumer with msConsumer set to LOCAL, sslCertificateValidation must be false."
                                    )
                                }
                            }
                        }
                        // (An omitted value is considered empty and acceptable.)
                    }
                    // For WebServiceConsumer, no further msConsumer validation is needed.
                }
            }
        }
}