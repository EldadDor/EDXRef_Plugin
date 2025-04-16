package com.github.edxref.inspection

import com.github.edxref.MyBundle
// Import settings and logger if you implemented them from previous steps
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.codeInspection.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

// Helper function for conditional logging (assuming you added this)
private fun logIfEnabled(project: Project, logger: Logger, message: String) {
    // Check settings only if the settings class exists
    try {
        if (project.getWSConsumerSettings().enableLog) {
            logger.info(message)
        }
    } catch (e: NoClassDefFoundError) {
        // Handle case where settings class might not be available yet or during tests
        logger.warn("WSConsumerSettings not found, logging disabled for this check.", e)
    } catch (e: Exception) {
        // Catch other potential exceptions during settings access
        logger.error("Error accessing WSConsumerSettings", e)
    }
}


// Define a quick fix for missing url and path (assuming you added this)
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
                    if (methodAttribute != null) { // Check if methodAttribute is not null
                        annotationElement.setDeclaredAttributeValue("method", methodAttribute)
                    }
                }

                // Optimize imports and reformat code
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(annotationElement)
            }
        } else if (!isJava && annotationElement is KtAnnotationEntry) {
            // Kotlin implementation
            val ktAnnotationEntry = annotationElement // Cast to correct type for clarity

            // Check if method attribute already exists
            val hasMethodAttribute = ktAnnotationEntry.valueArguments.any {
                it.getArgumentName()?.asName?.asString() == "method"
            }

            val psiFactory = KtPsiFactory(project)

            // Create the attribute to add
            val urlAttributeText = "url = \"http://localhost:8080/service\""

            // If there are existing arguments
            if (ktAnnotationEntry.valueArgumentList != null) {
                val argList = ktAnnotationEntry.valueArgumentList!!

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
                // Create a new argument list using pattern-based creation
                val methodText = if (hasMethodAttribute) "" else ", method = WSMethods.GET"
                val patternText = "@Annotation($urlAttributeText$methodText)"

                // Create the entire annotation with arguments using a pattern
                val dummyAnnotation = psiFactory.createAnnotationEntry(patternText)

                // Extract the value argument list and replace it in the original annotation
                val newArgumentList = dummyAnnotation.valueArgumentList
                if (newArgumentList != null) {
                    ktAnnotationEntry.add(newArgumentList)
                }
            }
        }
    }
}


interface WSConsumerInspectionLogic {
    private val log: Logger
        get() = logger<WSConsumerInspectionLogic>()

    fun checkWSConsumerAnnotation(
        project: Project, // Added project parameter
        annotationElement: PsiElement,
        holder: ProblemsHolder,
        urlValue: String,
        pathValue: String,
        sslCertificateValidation: Boolean,
        hasMsConsumer: Boolean,
        msConsumerValue: String,
        isPearlWebserviceConsumer: Boolean,
        isJava: Boolean // Added isJava parameter
    ) {
        // Base check: Ensure url or path is specified
        if (urlValue.isEmpty() && pathValue.isEmpty()) {
            logIfEnabled(project, log, "plugin.rules.missing.url.and.path")
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.missing.url.and.path", "Either 'url' or 'path' must be specified in @WSConsumer annotation."),
                ProblemHighlightType.ERROR, // Changed to ERROR
                AddDefaultUrlQuickFix(isJava) // Pass isJava
            )
            return // Return early as other rules depend on url/path
        }

        // Rule 1: If msConsumer is present, url should not be specified (use path only)
        if (hasMsConsumer && urlValue.isNotEmpty()) {
            logIfEnabled(project, log, "plugin.rules.url.with.msconsumer")
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.url.with.msconsumer", "For @WSConsumer with msConsumer, 'url' must not be specified; use 'path' only."),
                ProblemHighlightType.ERROR // Changed to ERROR
            )
        }

        // Rule 2: The 'path' should not contain protocol information
        if (pathValue.isNotEmpty() && (pathValue.contains("http://") || pathValue.contains("https://"))) {
            logIfEnabled(project, log, "plugin.rules.path.with.protocol")
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.path.with.protocol", "The 'path' attribute must not contain http/https; specify only a relative path."),
                ProblemHighlightType.ERROR // Changed to ERROR
            )
        }

        // Rule 3: URL should not contain double slashes (except in protocol)
        if (urlValue.isNotEmpty()) {
            val protocolIndex = urlValue.indexOf("://")
            // Check for double slashes *after* the protocol part
            if (protocolIndex >= 0 && urlValue.substring(protocolIndex + 3).contains("//")) {
                logIfEnabled(project, log, "plugin.rules.url.double.slashes")
                holder.registerProblem(
                    annotationElement,
                    MyBundle.message("plugin.rules.url.double.slashes", "The 'url' attribute contains invalid double slashes."),
                    ProblemHighlightType.ERROR // Changed to ERROR
                )
            }
        }

        // Rule 4: When only 'path' is specified, 'msConsumer' must be defined
        if (pathValue.isNotEmpty() && urlValue.isEmpty() && !hasMsConsumer) {
            logIfEnabled(project, log, "plugin.rules.path.without.msconsumer")
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.path.without.msconsumer", "When only 'path' is specified, 'msConsumer' must be defined."),
                ProblemHighlightType.ERROR // Changed to ERROR
            )
        }

        // Rule 5: Detect invalid URLs containing specific hosts
        if (urlValue.isNotEmpty()) {
            // Get the list of invalid hosts from settings (assuming you added settings)
            val invalidHostsStr = try {
                project.getWSConsumerSettings().invalidHosts
            } catch (e: Exception) {
                MyBundle.message("plugin.rules.invalid.address") // Fallback to bundle
            }

            val invalidHosts = invalidHostsStr.split(',').mapNotNull { it.trim().takeIf { it.isNotEmpty() } }

            for (host in invalidHosts) {
                // Simple contains check - might need refinement for accuracy (e.g., check host part only)
                if (urlValue.contains(host)) {
                    logIfEnabled(project, log, "plugin.rules.invalid.server: $host")
                    holder.registerProblem(
                        annotationElement,
                        MyBundle.message("plugin.rules.invalid.server", "Invalid URL: ''{0}'' is in the list of restricted servers.", host),
                        ProblemHighlightType.ERROR // Changed to ERROR
                    )
                    break // Report only the first match
                }
            }
        }

        // Rule 6: Further restrictions for PearlWebserviceConsumer
        if (isPearlWebserviceConsumer && hasMsConsumer) {
            // Check if the msConsumerValue contains any LbMsType *other than* PEARL or LOCAL.
            // This text-based check is somewhat fragile but matches the current data structure.
            // It assumes LbMsType enum values are CRM, CZ, BATCH, PEARL, LOCAL, NONE.
            val containsInvalidType = msConsumerValue.contains("CRM") ||
                    msConsumerValue.contains("CZ") ||
                    msConsumerValue.contains("BATCH") ||
                    msConsumerValue.contains("NONE") // Assuming NONE is also invalid here

            if (containsInvalidType) {
                // Found an invalid type like CRM, CZ, BATCH, or NONE
                logIfEnabled(project, log, "plugin.rules.pearl.msconsumer.invalid") // Use the updated key if you changed it
                holder.registerProblem(
                    // Try to highlight the msConsumer attribute value if possible
                    findHighlightElementForAttribute(annotationElement, "msConsumer") ?: annotationElement,
                    MyBundle.message("plugin.rules.pearl.msconsumer.invalid"), // Use the updated message
                    ProblemHighlightType.ERROR
                )
            } else {
                // No invalid types found (only PEARL, LOCAL, or both might be present)
                // Now check the SSL rule specifically if LOCAL is present.
                val containsLocal = msConsumerValue.contains("LOCAL")
                if (containsLocal && sslCertificateValidation) {
                    logIfEnabled(project, log, "plugin.rules.pearl.ssl.validation")
                    holder.registerProblem(
                        // Try to highlight the sslCertificateValidation attribute value
                        findHighlightElementForAttribute(annotationElement, "sslCertificateValidation") ?: annotationElement,
                        MyBundle.message("plugin.rules.pearl.ssl.validation", "For PearlWebserviceConsumer with msConsumer set to LOCAL, sslCertificateValidation must be false."),
                        ProblemHighlightType.ERROR
                    )
                }
                // If only PEARL is present, or if LOCAL is present with sslCertificateValidation=false, no error is reported here.
            }
        }
        // Rule 7: Disallow PEARL LbMsType for regular WebserviceConsumer (remains the same)
        else if (!isPearlWebserviceConsumer && hasMsConsumer && msConsumerValue.contains("PEARL")) {
            logIfEnabled(project, log, "plugin.rules.non.pearl.consumer")
            holder.registerProblem(
                findHighlightElementForAttribute(annotationElement, "msConsumer") ?: annotationElement,
                MyBundle.message("plugin.rules.non.pearl.consumer", "'PEARL' LbMsType is not allowed for WebserviceConsumer"),
                ProblemHighlightType.ERROR
            )
        }
    }
}

private fun findHighlightElementForAttribute(annotationElement: PsiElement, attributeName: String): PsiElement? {
    return when (annotationElement) {
        is PsiAnnotation -> annotationElement.findAttributeValue(attributeName)
        is KtAnnotationEntry -> annotationElement.valueArguments.find {
            // CORRECTED PATH: ValueArgument -> ValueArgumentName -> Name -> String
            it.getArgumentName()?.asName?.asString() == attributeName
        }?.getArgumentExpression() // Get the expression (value) part of the argument
        else -> null
    } ?: annotationElement // Fallback to the whole annotation if attribute not found
}

// Java implementation
class WSConsumerJavaInspection : AbstractBaseJavaLocalInspectionTool(), WSConsumerInspectionLogic {
    private val log = logger<WSConsumerJavaInspection>()
    override fun getDisplayName(): String = "WSConsumer annotation inspection (Java)"

    // checkClass is generally less preferred for detailed annotation checks than buildVisitor
    // It runs less frequently and might miss some contexts.
    // Consider removing or simplifying checkClass if buildVisitor covers all cases.
    override fun checkClass(psiClass: PsiClass, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor> {
        val project = psiClass.project
        val annotations = psiClass.annotations
        for (annotation in annotations) {
            if (annotation.qualifiedName?.endsWith("WSConsumer") == true) {
                logIfEnabled(project, log, "Found WSConsumer annotation, checkClass")
                // Get properties by name instead of by type
                val urlValue = annotation.findAttributeValue("url")
                val pathValue = annotation.findAttributeValue("path")

                // Simplified check: If neither url nor path attribute exists AT ALL.
                // More detailed validation (empty values) is handled in buildVisitor.
                if (urlValue == null && pathValue == null) {
                    logIfEnabled(project, log, "plugin.rules.missing.url.and.path (checkClass)")
                    // Note: QuickFix might not work reliably from checkClass
                    return arrayOf(
                        manager.createProblemDescriptor(
                            annotation,
                            MyBundle.message("plugin.rules.missing.url.and.path", "Either 'url' or 'path' must be specified in @WSConsumer annotation."),
                            true, // show tooltip?
                            ProblemHighlightType.ERROR, // Changed to ERROR
                            isOnTheFly
                            // AddDefaultUrlQuickFix(true) // QuickFix might be less reliable here
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
                val project = annotation.project
                logIfEnabled(project, log, "Found WSConsumer annotation, visit")

                if (annotation.qualifiedName?.endsWith("WSConsumer") != true) {
                    return
                }

                // Get the annotated element - Ensure it's a class or interface
                val modifierList = annotation.parent as? PsiModifierList
                val annotatedElement = modifierList?.parent as? PsiClass ?: return // Ensure it's a PsiClass

                // Check if annotated element implements PearlWebserviceConsumer
                val isPearlWebserviceConsumer = isPearlWebserviceConsumer(annotatedElement)

                // Extract annotation attributes
                val urlAttr = annotation.findAttributeValue("url")
                val urlValue = if (urlAttr is PsiLiteralExpression) {
                    urlAttr.value?.toString() ?: ""
                } else {
                    // Handle cases where url might be a constant reference, etc.
                    // For simplicity, treat non-literal as empty for now, or add more complex resolution
                    ""
                }

                val pathAttr = annotation.findAttributeValue("path")
                val pathValue = if (pathAttr is PsiLiteralExpression) {
                    pathAttr.value?.toString() ?: ""
                } else {
                    ""
                }

                // Handle sslCertificateValidation - default is true if not specified
                val sslValue = annotation.findAttributeValue("sslCertificateValidation")
                val sslCertificateValidation = if (sslValue is PsiLiteralExpression) {
                    sslValue.value as? Boolean ?: true // Default to true if attribute exists but isn't boolean literal
                } else {
                    true // Default to true if attribute doesn't exist
                }

                // Check for msConsumer - FIXED to check for non-empty array
                val msConsumerAttr = annotation.findAttributeValue("msConsumer")
                // Check if attribute exists AND its text representation is not an empty array initializer
                val hasMsConsumer = msConsumerAttr != null &&
                        msConsumerAttr.text.isNotBlank() && // Ensure not just whitespace
                        !msConsumerAttr.text.equals("{}") &&
                        !msConsumerAttr.text.equals("[]") // Consider empty array case if applicable
                val msConsumerValue = msConsumerAttr?.text ?: ""

                // Use the common logic
                checkWSConsumerAnnotation(
                    project,
                    annotation,
                    holder,
                    urlValue,
                    pathValue,
                    sslCertificateValidation,
                    hasMsConsumer,
                    msConsumerValue,
                    isPearlWebserviceConsumer,
                    true // isJava = true
                )
            }

            private fun isPearlWebserviceConsumer(element: PsiClass): Boolean { // Changed parameter to PsiClass
                val project = element.project
                // Check implemented interfaces
                for (implemented in element.interfaces) {
                    if (implemented.qualifiedName?.endsWith("PearlWebserviceConsumer") == true) {
                        logIfEnabled(project, log, "Found PearlWebServiceConsumer class, implement")
                        return true
                    }
                }

                // Check superclass hierarchy
                var superClass = element.superClass
                while (superClass != null) {
                    if (superClass.qualifiedName?.endsWith("PearlWebserviceConsumer") == true) {
                        logIfEnabled(project, log, "Found PearlWebServiceConsumer FQN class")
                        return true
                    }
                    // Check interfaces of superclasses too
                    for (implemented in superClass.interfaces) {
                        if (implemented.qualifiedName?.endsWith("PearlWebserviceConsumer") == true) {
                            logIfEnabled(project, log, "Found PearlWebServiceConsumer class via superclass interface")
                            return true
                        }
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
    private val log = logger<WSConsumerKotlinInspection>() // Added logger instance

    override fun getDisplayName(): String = "WSConsumer annotation inspection (Kotlin)"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {
            override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
                super.visitAnnotationEntry(annotationEntry)

                val project = annotationEntry.project

                // Resolve the annotation's FQN to be more precise
                val annotationFQN = annotationEntry.calleeExpression
                    ?.constructorReferenceExpression
                    ?.getReferencedNameAsName() // Get short name
                    // Add proper resolution logic if needed, e.g., using binding context
                    // For now, stick to short name check for simplicity
                    ?.asString()

                if (annotationFQN != "WSConsumer") return // Check for exact short name

                // Locate the containing class/interface (the annotated type)
                val ktClass: KtClass = annotationEntry.getStrictParentOfType() ?: return

                // Check if the class implements PearlWebserviceConsumer
                // This check might need refinement using resolved types for accuracy
                val isPearlWebserviceConsumer = ktClass.superTypeListEntries.any {
                    it.typeReference?.text?.contains("PearlWebserviceConsumer") == true
                }

                // Helper to extract the literal text for a named attribute
                fun getArgumentText(name: String): String {
                    val arg = annotationEntry.valueArguments.find {
                        it.getArgumentName()?.asName?.asString() == name
                    } ?: return "" // Return empty if argument not found

                    // Get the expression text, handle string literals
                    val expr = arg.getArgumentExpression()
                    val text = expr?.text ?: ""
                    return if (expr is org.jetbrains.kotlin.psi.KtStringTemplateExpression && text.startsWith("\"") && text.endsWith("\"")) {
                        text.substring(1, text.length - 1) // Remove quotes for string literals
                    } else {
                        text // Return raw text for other expressions (booleans, enum constants, etc.)
                    }
                }

                val urlValue = getArgumentText("url")
                val pathValue = getArgumentText("path")

                // When not provided, sslCertificateValidation defaults to true
                val sslText = getArgumentText("sslCertificateValidation")
                val sslCertificateValidation = if (sslText.isEmpty()) true else sslText.equals("true", ignoreCase = true)

                // Determine if the child annotation msConsumer is explicitly provided - FIXED
                val msConsumerArg = annotationEntry.valueArguments.find {
                    it.getArgumentName()?.asName?.asString() == "msConsumer"
                }

                // Only consider msConsumer present if it's non-empty array/collection literal
                val msConsumerExprText = msConsumerArg?.getArgumentExpression()?.text
                val hasMsConsumer = msConsumerExprText != null &&
                        msConsumerExprText.isNotBlank() &&
                        !msConsumerExprText.matches(Regex("""\[\s*]""")) && // Check for empty array []
                        !msConsumerExprText.matches(Regex("""\{\s*}""")) && // Check for empty block {} (less likely but possible)
                        !msConsumerExprText.equals("arrayOf()", ignoreCase = true) // Check for empty arrayOf()

                val msConsumerValue = msConsumerExprText ?: ""

                // Use the common logic
                checkWSConsumerAnnotation(
                    project,
                    annotationEntry,
                    holder,
                    urlValue,
                    pathValue,
                    sslCertificateValidation,
                    hasMsConsumer,
                    msConsumerValue,
                    isPearlWebserviceConsumer,
                    false // isJava = false
                )
            }
        }
}
