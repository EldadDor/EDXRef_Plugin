package com.github.edxref.inspection

import com.intellij.codeInspection.*
import com.intellij.psi.*
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

interface WSConsumerInspectionLogic {
    fun checkWSConsumerAnnotation(
        annotationElement: PsiElement,
        holder: ProblemsHolder,
        urlValue: String,
        pathValue: String,
        sslCertificateValidation: Boolean,
        hasMsConsumer: Boolean,
        msConsumerValue: String,
        isPearlWebserviceConsumer: Boolean
    ) {
        // Base case check: Either URL or path must be specified
        if (urlValue.isEmpty() && pathValue.isEmpty()) {
            holder.registerProblem(
                annotationElement,
                "Either 'url' or 'path' must be specified in @WSConsumer annotation."
            )
            return
        }

        // Rule 1: If msConsumer is present, url should not be specified (use path only)
        if (hasMsConsumer && urlValue.isNotEmpty()) {
            holder.registerProblem(
                annotationElement,
                "For @WSConsumer with msConsumer, 'url' must not be specified; use 'path' only."
            )
        }

        // Rule 2: The 'path' should not contain protocol information
        if (pathValue.isNotEmpty() && (pathValue.contains("http://") || pathValue.contains("https://"))) {
            holder.registerProblem(
                annotationElement,
                "The 'path' attribute must not contain http/https; specify only a relative path."
            )
        }

        // Rule 3: URL should not contain double slashes (except in protocol)
        if (urlValue.isNotEmpty()) {
            val protocolIndex = urlValue.indexOf("://")
            if (protocolIndex > 0 && urlValue.substring(protocolIndex + 3).contains("//")) {
                holder.registerProblem(
                    annotationElement,
                    "The 'url' attribute contains invalid double slashes."
                )
            }
        }

        // Rule 4: When only 'path' is specified, 'msConsumer' must be defined
        if (pathValue.isNotEmpty() && urlValue.isEmpty() && !hasMsConsumer) {
            holder.registerProblem(
                annotationElement,
                "When only 'path' is specified, 'msConsumer' must be defined."
            )
        }

        // Rule 5: Detect invalid URLs containing specific hosts
        if (urlValue.isNotEmpty()) {
            val invalidHosts = listOf("msdevcz", "msdevcrm")
            for (host in invalidHosts) {
                if (urlValue.contains(host)) {
                    holder.registerProblem(
                        annotationElement,
                        "Invalid URL: '$host' is in the list of restricted servers."
                    )
                    break
                }
            }
        }

        // Rule 6: Further restrictions for PearlWebserviceConsumer
        if (isPearlWebserviceConsumer) {
            if (hasMsConsumer) {
                // Rule 6.1: If a value is provided for msConsumer in PearlWebserviceConsumer, it must be LOCAL
                val hasLocal = msConsumerValue.contains("LOCAL")
                if (msConsumerValue.isNotEmpty() && !hasLocal) {
                    holder.registerProblem(
                        annotationElement,
                        "When used in PearlWebserviceConsumer, msConsumer value may only be omitted or set to LOCAL."
                    )
                } else if (hasLocal && sslCertificateValidation) {
                    // Rule 6.2: For LOCAL msConsumer in PearlWebserviceConsumer, sslCertificateValidation must be false
                    holder.registerProblem(
                        annotationElement,
                        "For PearlWebserviceConsumer with msConsumer set to LOCAL, sslCertificateValidation must be false."
                    )
                }
            }
        } else {
            // Rule 7: Disallow PEARL LbMsType for regular WebserviceConsumer
            if (hasMsConsumer && msConsumerValue.contains("PEARL")) {
                holder.registerProblem(
                    annotationElement,
                    "'PEARL' LbMsType is not allowed for WebserviceConsumer"
                )
            }
        }
    }
}

// Java implementation
class WSConsumerJavaInspection : AbstractBaseJavaLocalInspectionTool(), WSConsumerInspectionLogic {
    override fun getDisplayName(): String = "WSConsumer annotation inspection (Java)"

    override fun checkClass(psiClass: PsiClass, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor> {
        val annotations = psiClass.annotations
        for (annotation in annotations) {
            if (annotation.qualifiedName?.endsWith("WSConsumer") == true) {
                // Get properties by name instead of by type
                val urlValue = annotation.findAttributeValue("url")
                val pathValue = annotation.findAttributeValue("path")

                if (urlValue == null && pathValue == null) {
                    return arrayOf(
                        manager.createProblemDescriptor(
                            annotation,
                            "Either 'url' or 'path' must be specified in @WSConsumer annotation.",
                            true,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            isOnTheFly
                        )
                    )
                }
            }
        }
        return ProblemDescriptor.EMPTY_ARRAY
    }

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        session: LocalInspectionToolSession
    ): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
                if (annotation.qualifiedName?.endsWith("WSConsumer") != true) {
                    return
                }

                // Get the annotated element - Fix for the 'parent' error
                val modifierList = annotation.parent as? PsiModifierList
                val annotatedElement = modifierList?.parent as? PsiModifierListOwner ?: return

                // Check if annotated element implements PearlWebserviceConsumer
                val isPearlWebserviceConsumer = isPearlWebserviceConsumer(annotatedElement)

                // Extract annotation attributes
                val urlAttr = annotation.findAttributeValue("url")
                val urlValue = if (urlAttr != null && urlAttr is PsiLiteralExpression) {
                    urlAttr.value?.toString() ?: ""
                } else {
                    ""
                }

                val pathAttr = annotation.findAttributeValue("path")
                val pathValue = if (pathAttr != null && pathAttr is PsiLiteralExpression) {
                    pathAttr.value?.toString() ?: ""
                } else {
                    ""
                }

                // Handle sslCertificateValidation - default is true if not specified
                val sslValue = annotation.findAttributeValue("sslCertificateValidation")
                val sslCertificateValidation = if (sslValue is PsiLiteralExpression) {
                    sslValue.value as? Boolean ?: true
                } else {
                    true
                }

                // Check for msConsumer
                val msConsumerAttr = annotation.findAttributeValue("msConsumer")
                val hasMsConsumer = msConsumerAttr != null
                val msConsumerValue = msConsumerAttr?.text ?: ""

                // Use the common logic
                checkWSConsumerAnnotation(
                    annotation,
                    holder,
                    urlValue,
                    pathValue,
                    sslCertificateValidation,
                    hasMsConsumer,
                    msConsumerValue,
                    isPearlWebserviceConsumer
                )
            }

            private fun isPearlWebserviceConsumer(element: PsiModifierListOwner): Boolean {
                if (element !is PsiClass) return false

                // Check implemented interfaces
                for (implemented in element.interfaces) {
                    if (implemented.qualifiedName?.endsWith("PearlWebserviceConsumer") == true) {
                        return true
                    }
                }

                // Check superclass hierarchy
                var superClass = element.superClass
                while (superClass != null) {
                    if (superClass.qualifiedName?.endsWith("PearlWebserviceConsumer") == true) {
                        return true
                    }
                    superClass = superClass.superClass
                }

                return false
            }
        }
    }
}

// Kotlin implementation
class WSConsumerKotlinInspection : AbstractKotlinInspection(), WSConsumerInspectionLogic {
    override fun getDisplayName(): String = "WSConsumer annotation inspection (Kotlin)"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {
            override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
                super.visitAnnotationEntry(annotationEntry)

                // Process only annotations with the short name "WSConsumer"
                val shortName = annotationEntry.shortName?.asString() ?: return
                if (!shortName.endsWith("WSConsumer")) return

                // Locate the containing class/interface (the annotated type)
                val ktClass: KtClass = annotationEntry.getStrictParentOfType() ?: return

                // Check if the class implements PearlWebserviceConsumer
                val isPearlWebserviceConsumer = ktClass.superTypeListEntries.any {
                    it.typeReference?.text?.contains("PearlWebserviceConsumer") == true
                }

                // Helper to extract the literal text for a named attribute
                fun getArgumentText(name: String): String {
                    val arg = annotationEntry.valueArguments.find {
                        it.getArgumentName()?.asName?.asString() == name
                    } ?: return ""

                    val text = arg.getArgumentExpression()?.text ?: ""
                    return if (text.startsWith("\"") && text.endsWith("\"")) {
                        text.substring(1, text.length - 1)
                    } else {
                        text
                    }
                }

                val urlValue = getArgumentText("url")
                val pathValue = getArgumentText("path")

                // When not provided, sslCertificateValidation defaults to true
                val sslText = getArgumentText("sslCertificateValidation")
                val sslCertificateValidation = if (sslText.isEmpty()) true else sslText.toBoolean()

                // Determine if the child annotation msConsumer is explicitly provided
                val msConsumerArg = annotationEntry.valueArguments.find {
                    it.getArgumentName()?.asName?.asString() == "msConsumer"
                }

                val hasMsConsumer = msConsumerArg != null
                val msConsumerValue = msConsumerArg?.getArgumentExpression()?.text ?: ""

                // Use the common logic
                checkWSConsumerAnnotation(
                    annotationEntry,
                    holder,
                    urlValue,
                    pathValue,
                    sslCertificateValidation,
                    hasMsConsumer,
                    msConsumerValue,
                    isPearlWebserviceConsumer
                )
            }
        }
}
