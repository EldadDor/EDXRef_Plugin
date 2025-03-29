package com.github.edxref.inspection

import com.github.edxref.MyBundle
import com.intellij.codeInspection.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.bootstrap.logEssentialInfoAboutIde
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType


class AddDefaultUrlQuickFix(private val isJava: Boolean) : LocalQuickFix {
    override fun getName(): String = "Add default URL attribute"
    override fun getFamilyName(): String = name

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val annotationElement = descriptor.psiElement
        if (isJava && annotationElement is PsiAnnotation) {
            // Java implementation
            val factory = JavaPsiFacade.getElementFactory(project)
            val attr = factory.createAnnotationFromText("@WSConsumer(url = \"http://localhost:8080/service\", method = WSMethods.GET)", null)
            val urlAttribute = attr.findAttributeValue("url")

            // Add URL attribute to the annotation
            if (urlAttribute != null) {
                annotationElement.setDeclaredAttributeValue("url", urlAttribute)

                // Add method attribute if it doesn't exist
                if (annotationElement.findAttributeValue("method") == null) {
                    val methodAttribute = attr.findAttributeValue("method")
                    annotationElement.setDeclaredAttributeValue("method", methodAttribute)
                }

                // Optimize imports and reformat code
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(annotationElement)
            }
        } else if (!isJava && annotationElement is KtAnnotationEntry) {
            // Kotlin implementation
            val hasMethodAttribute = annotationElement.valueArguments.any {
                it.getArgumentName()?.asName?.asString() == "method"
            }

            val psiFactory = KtPsiFactory(project)

            // Create the attribute to add
            val urlAttributeText = "url = \"http://localhost:8080/service\""

            // If there are existing arguments
            if (annotationElement.valueArgumentList != null) {
                val argList = annotationElement.valueArgumentList!!

                // Insert url attribute at the beginning
                val newArgument = psiFactory.createArgument(urlAttributeText)
                if (argList.arguments.isNotEmpty()) {
                    argList.addArgumentBefore(newArgument, argList.arguments.first())
                } else {
                    argList.addArgument(newArgument)
                }

                // Add method attribute if it doesn't exist
                if (!hasMethodAttribute) {
                    val methodArgument = psiFactory.createArgument("method = WSMethods.GET")
                    argList.addArgument(methodArgument)
                }
            } else {
                // Create a new argument list
                val methodText = if (hasMethodAttribute) "" else ", method = WSMethods.GET"
                val argumentListText = "($urlAttributeText$methodText)"
                val newArgumentList = psiFactory.createValueArgumentListByPattern(argumentListText)
                annotationElement.valueArgumentList?.replace(newArgumentList)
                    ?: annotationElement.add(newArgumentList)
            }
        }
    }
}


interface WSConsumerInspectionLogic {
    private val log: Logger
        get() = logger<WSConsumerInspectionLogic>()

    fun checkWSConsumerAnnotation(
        annotationElement: PsiElement,
        holder: ProblemsHolder,
        urlValue: String,
        pathValue: String,
        sslCertificateValidation: Boolean,
        hasMsConsumer: Boolean,
        msConsumerValue: String,
        isPearlWebserviceConsumer: Boolean
    )
    {
        // Base check: Ensure url or path is specified
        if (urlValue.isEmpty() && pathValue.isEmpty()) {
            log.info("plugin.rules.missing.url.and.path")
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.missing.url.and.path", "Either 'url' or 'path' must be specified in @WSConsumer annotation.")
            )
            return
        }

        // Rule 1: If msConsumer is present, url should not be specified (use path only)
        if (hasMsConsumer && urlValue.isNotEmpty()) {
            log.info("plugin.rules.url.with.msconsumer")
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.url.with.msconsumer", "For @WSConsumer with msConsumer, 'url' must not be specified; use 'path' only.")
            )
        }

        // Rule 2: The 'path' should not contain protocol information
        if (pathValue.isNotEmpty() && (pathValue.contains("http://") || pathValue.contains("https://"))) {
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.path.with.protocol", "The 'path' attribute must not contain http/https; specify only a relative path.")
            )
        }

        // Rule 3: URL should not contain double slashes (except in protocol)
        if (urlValue.isNotEmpty()) {
            val protocolIndex = urlValue.indexOf("://")
            if (protocolIndex > 0 && urlValue.substring(protocolIndex + 3).contains("//")) {
                holder.registerProblem(
                    annotationElement,
                    MyBundle.message("plugin.rules.url.double.slashes", "The 'url' attribute contains invalid double slashes.")
                )
            }
        }

        // Rule 4: When only 'path' is specified, 'msConsumer' must be defined
        if (pathValue.isNotEmpty() && urlValue.isEmpty() && !hasMsConsumer) {
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.path.without.msconsumer", "When only 'path' is specified, 'msConsumer' must be defined.")
            )
        }

        // Rule 5: Detect invalid URLs containing specific hosts
        if (urlValue.isNotEmpty()) {
            // Get the list of invalid hosts from the bundle property
            val invalidHostsStr = MyBundle.message("plugin.rules.invalid.address")
            val invalidHosts = invalidHostsStr.split(',').map { it.trim() }

            for (host in invalidHosts) {
                if (urlValue.contains(host)) {
                    holder.registerProblem(
                        annotationElement,
                        MyBundle.message("plugin.rules.invalid.server", "Invalid URL: ''{0}'' is in the list of restricted servers.", host)
                    )
                    break
                }
            }
        }

        // Rule 6: Further restrictions for PearlWebserviceConsumer
        if (isPearlWebserviceConsumer && hasMsConsumer) {
            // If a value is provided for msConsumer, it must be LOCAL
            val hasLocal = msConsumerValue.contains("LOCAL")
            if (msConsumerValue.isNotEmpty() && !hasLocal) {
                holder.registerProblem(
                    annotationElement,
                    MyBundle.message("plugin.rules.pearl.msconsumer.local", "When used in PearlWebserviceConsumer, msConsumer value may only be omitted or set to LOCAL.")
                )
            } else if (hasLocal && sslCertificateValidation) {
                holder.registerProblem(
                    annotationElement,
                    MyBundle.message("plugin.rules.pearl.ssl.validation", "For PearlWebserviceConsumer with msConsumer set to LOCAL, sslCertificateValidation must be false.")
                )
            }
        } else if (!isPearlWebserviceConsumer && hasMsConsumer && msConsumerValue.contains("PEARL")) {
            // Rule 7: Disallow PEARL LbMsType for regular WebserviceConsumer
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.non.pearl.consumer", "'PEARL' LbMsType is not allowed for WebserviceConsumer")
            )
        }
    }
}

// Java implementation
class WSConsumerJavaInspection : AbstractBaseJavaLocalInspectionTool(), WSConsumerInspectionLogic {
    private val log = logger<WSConsumerJavaInspection>()
    override fun getDisplayName(): String = "WSConsumer annotation inspection (Java)"

    override fun checkClass(psiClass: PsiClass, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor> {
        val annotations = psiClass.annotations
        for (annotation in annotations) {
            if (annotation.qualifiedName?.endsWith("WSConsumer") == true) {
                log.info("Found WSConsumer annotation, checkClass")
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
                            isOnTheFly,
                            AddDefaultUrlQuickFix(true)
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
                log.info("Found WSConsumer annotation, visit")
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

                // Check for msConsumer - FIXED to check for non-empty array
                val msConsumerAttr = annotation.findAttributeValue("msConsumer")
                val hasMsConsumer = msConsumerAttr != null &&
                        !msConsumerAttr.text.equals("{}") &&
                        !msConsumerAttr.text.equals("[]") &&
                        msConsumerAttr.text.isNotEmpty()
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
                        log.info("Found PearlWebServiceConsumer class, implement")
                        return true
                    }
                }

                // Check superclass hierarchy
                var superClass = element.superClass
                while (superClass != null) {
                    if (superClass.qualifiedName?.endsWith("PearlWebserviceConsumer") == true) {
                        log.info("Found PearlWebServiceConsumer FQN class")
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

                // Determine if the child annotation msConsumer is explicitly provided - FIXED
                val msConsumerArg = annotationEntry.valueArguments.find {
                    it.getArgumentName()?.asName?.asString() == "msConsumer"
                }

                // Only consider msConsumer present if it's non-empty
                val hasMsConsumer = msConsumerArg != null &&
                        msConsumerArg.getArgumentExpression()?.text?.let {
                            it.isNotEmpty() && !it.equals("{}") && !it.equals("[]")
                        } ?: false
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
