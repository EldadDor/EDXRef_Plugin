package com.github.edxref.inspection

// Import settings and logger helper if you have them
// import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
// Removed incorrect PsiUtil import if it was added
import com.github.edxref.MyBundle
import com.github.edxref.settings.WSConsumerSettings
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.codeInspection.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

// --- Logger Helper ---
private fun logIfEnabled(project: Project, logger: Logger, message: String) {
    try {
        if (project.getWSConsumerSettings().enableLog) {
            logger.info(message)
        }
    } catch (e: Exception) {
        // Ignore exceptions during logging check
    }
}

// --- Settings Accessors with Fallbacks ---
// (Assuming these are defined correctly as in previous steps)
private fun getSettings(project: Project): WSConsumerSettings {
    return project.getWSConsumerSettings()
}

private fun getWsConsumerAnnotationFqn(project: Project): String {
    // Simplified - replace with actual implementation using defaults if needed
    return getSettings(project).wsConsumerAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSConsumer" } // Added fallback
}

private fun getWebserviceConsumerFqn(project: Project): String {
    // Simplified - replace with actual implementation using defaults if needed
    return getSettings(project).webserviceConsumerFqn.ifBlank { "com.github.edxref.annotations.WebserviceConsumer" } // Added fallback
}

private fun getPearlWebserviceConsumerFqn(project: Project): String {
    // Simplified - replace with actual implementation using defaults if needed
    return getSettings(project).pearlWebserviceConsumerFqn.ifBlank { "com.github.edxref.annotations.PearlWebserviceConsumer" } // Added fallback
}

// ... other settings accessors ...
private fun getPropertyFqn(project: Project): String {
    // Simplified - replace with actual implementation using defaults if needed
    return getSettings(project).propertyAnnotationFqn.ifBlank { "com.github.edxref.annotations.Property" } // Added fallback
}


// --- Quick Fix (Assuming AddDefaultUrlQuickFix is defined correctly) ---
class AddDefaultUrlQuickFix(private val isJava: Boolean) : LocalQuickFix {
    override fun getName(): String = "Add default URL attribute"
    override fun getFamilyName(): String = name

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val annotationElement = descriptor.psiElement
        if (isJava && annotationElement is PsiAnnotation) {
            // Java implementation
            val factory = JavaPsiFacade.getElementFactory(project)
            // Ensure WSMethods is resolvable or use FQN
            val attr = factory.createAnnotationFromText("@WSConsumer(url = \"http://localhost:8080/service\", method = com.example.WSMethods.GET)", null) // ADJUST FQN if needed
            val urlAttribute = attr.findAttributeValue("url")

            if (urlAttribute != null) {
                annotationElement.setDeclaredAttributeValue("url", urlAttribute)
                if (annotationElement.findAttributeValue("method") == null) {
                    val methodAttribute = attr.findAttributeValue("method")
                    if (methodAttribute != null) {
                        annotationElement.setDeclaredAttributeValue("method", methodAttribute)
                    }
                }
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(annotationElement)
            }
        } else if (!isJava && annotationElement is KtAnnotationEntry) {
            // Kotlin implementation
            val ktAnnotationEntry = annotationElement
            val hasMethodAttribute = ktAnnotationEntry.valueArguments.any {
                // Use corrected access path here too
                it.getArgumentName()?.asName?.asString() == "method"
            }
            val psiFactory = KtPsiFactory(project)
            val urlAttributeText = "url = \"http://localhost:8080/service\""

            if (ktAnnotationEntry.valueArgumentList != null) {
                val argList = ktAnnotationEntry.valueArgumentList!!
                val newArgument = psiFactory.createArgument(urlAttributeText)
                if (argList.arguments.isNotEmpty()) {
                    argList.addArgumentBefore(newArgument, argList.arguments.first())
                } else {
                    argList.addArgument(newArgument)
                }
                if (!hasMethodAttribute) {
                    // Ensure WSMethods is resolvable or use FQN
                    val methodArgument = psiFactory.createArgument("method = com.example.WSMethods.GET") // ADJUST FQN if needed
                    argList.addArgument(methodArgument)
                }
            } else {
                val methodText = if (hasMethodAttribute) "" else ", method = com.example.WSMethods.GET" // ADJUST FQN if needed
                val patternText = "@Annotation($urlAttributeText$methodText)"
                val dummyAnnotation = psiFactory.createAnnotationEntry(patternText)
                val newArgumentList = dummyAnnotation.valueArgumentList
                if (newArgumentList != null) {
                    ktAnnotationEntry.add(newArgumentList)
                }
            }
        }
    }
}


// --- Helper function to find attribute value for highlighting (CORRECTED AGAIN) ---
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


// --- Main Inspection Logic Interface ---
interface WSConsumerInspectionLogic {
    private val log: Logger // Use private val for logger within the interface scope
        get() = logger<WSConsumerInspectionLogic>() // Default logger instance

    fun checkWSConsumerAnnotation(
        project: Project,
        annotationElement: PsiElement,
        holder: ProblemsHolder,
        urlValue: String,
        pathValue: String,
        sslCertificateValidation: Boolean,
        hasMsConsumer: Boolean,
        msConsumerValue: String,
        isPearlWebserviceConsumer: Boolean,
        isJava: Boolean
    ) {
        val wsConsumerAnnotationFqn = getWsConsumerAnnotationFqn(project) // Get FQN from settings

        // Base check: Ensure url or path is specified
        if (urlValue.isEmpty() && pathValue.isEmpty()) {
            logIfEnabled(project, log, "plugin.rules.missing.url.and.path")
            holder.registerProblem(
                annotationElement,
                MyBundle.message("plugin.rules.missing.url.and.path", "Either 'url' or 'path' must be specified in @${wsConsumerAnnotationFqn.substringAfterLast('.')} annotation."),
                ProblemHighlightType.ERROR,
                AddDefaultUrlQuickFix(isJava)
            )
            return
        }

        // Rule 1: If msConsumer is present, url should not be specified (use path only)
        if (hasMsConsumer && urlValue.isNotEmpty()) {
            logIfEnabled(project, log, "plugin.rules.url.with.msconsumer")
            holder.registerProblem(
                findHighlightElementForAttribute(annotationElement, "url") ?: annotationElement,
                MyBundle.message("plugin.rules.url.with.msconsumer", "For @${wsConsumerAnnotationFqn.substringAfterLast('.')} with msConsumer, 'url' must not be specified; use 'path' only."),
                ProblemHighlightType.ERROR
            )
        }

        // Rule 2: The 'path' should not contain protocol information
        if (pathValue.isNotEmpty() && (pathValue.contains("http://") || pathValue.contains("https://"))) {
            logIfEnabled(project, log, "plugin.rules.path.with.protocol")
            holder.registerProblem(
                findHighlightElementForAttribute(annotationElement, "path") ?: annotationElement,
                MyBundle.message("plugin.rules.path.with.protocol", "The 'path' attribute must not contain http/https; specify only a relative path."),
                ProblemHighlightType.ERROR
            )
        }

        // Rule 3: URL should not contain double slashes (except in protocol)
        if (urlValue.isNotEmpty()) {
            val protocolIndex = urlValue.indexOf("://")
            if (protocolIndex >= 0 && urlValue.substring(protocolIndex + 3).contains("//")) {
                logIfEnabled(project, log, "plugin.rules.url.double.slashes")
                holder.registerProblem(
                    findHighlightElementForAttribute(annotationElement, "url") ?: annotationElement,
                    MyBundle.message("plugin.rules.url.double.slashes", "The 'url' attribute contains invalid double slashes."),
                    ProblemHighlightType.ERROR
                )
            }
        }

        // Rule 4: When only 'path' is specified, 'msConsumer' must be defined
        if (pathValue.isNotEmpty() && urlValue.isEmpty() && !hasMsConsumer) {
            logIfEnabled(project, log, "plugin.rules.path.without.msconsumer")
            holder.registerProblem(
                findHighlightElementForAttribute(annotationElement, "path") ?: annotationElement,
                MyBundle.message("plugin.rules.path.without.msconsumer", "When only 'path' is specified, 'msConsumer' must be defined."),
                ProblemHighlightType.ERROR
            )
        }

        // Rule 5: Detect invalid URLs containing specific hosts
        if (urlValue.isNotEmpty()) {
            val invalidHostsStr = getSettings(project).invalidHosts // Get from settings
            val invalidHosts = invalidHostsStr.split(',').mapNotNull { it.trim().takeIf { it.isNotEmpty() } }

            for (host in invalidHosts) {
                if (urlValue.contains(host)) {
                    logIfEnabled(project, log, "plugin.rules.invalid.server: $host")
                    holder.registerProblem(
                        findHighlightElementForAttribute(annotationElement, "url") ?: annotationElement,
                        MyBundle.message("plugin.rules.invalid.server", "Invalid URL: ''{0}'' is in the list of restricted servers.", host),
                        ProblemHighlightType.ERROR
                    )
                    break
                }
            }
        }

        // Rule 6: Further restrictions for PearlWebserviceConsumer
        if (isPearlWebserviceConsumer && hasMsConsumer) {
            val containsInvalidType = msConsumerValue.contains("CRM") ||
                    msConsumerValue.contains("CZ") ||
                    msConsumerValue.contains("BATCH") ||
                    msConsumerValue.contains("NONE")

            if (containsInvalidType) {
                logIfEnabled(project, log, "plugin.rules.pearl.msconsumer.invalid")
                holder.registerProblem(
                    findHighlightElementForAttribute(annotationElement, "msConsumer") ?: annotationElement,
                    MyBundle.message("plugin.rules.pearl.msconsumer.invalid"), // Use updated message
                    ProblemHighlightType.ERROR
                )
            } else {
                val containsLocal = msConsumerValue.contains("LOCAL")
                if (containsLocal && sslCertificateValidation) {
                    logIfEnabled(project, log, "plugin.rules.pearl.ssl.validation")
                    holder.registerProblem(
                        findHighlightElementForAttribute(annotationElement, "sslCertificateValidation") ?: annotationElement,
                        MyBundle.message("plugin.rules.pearl.ssl.validation", "For PearlWebserviceConsumer with msConsumer set to LOCAL, sslCertificateValidation must be false."),
                        ProblemHighlightType.ERROR
                    )
                }
            }
        }
        // Rule 7: Disallow PEARL LbMsType for regular WebserviceConsumer
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

// --- Java Implementation ---
class WSConsumerJavaInspection : AbstractBaseJavaLocalInspectionTool(), WSConsumerInspectionLogic {
    // No need to override log here, interface provides default

    override fun getDisplayName(): String = "WSConsumer Annotation Validation (Java)" // More specific display name

    // checkClass implementation (consider simplifying or removing if buildVisitor is sufficient)
    override fun checkClass(psiClass: PsiClass, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor> {
        val project = psiClass.project
        val wsConsumerAnnotationFqn = getWsConsumerAnnotationFqn(project)
        val annotations = psiClass.annotations
        for (annotation in annotations) {
            if (annotation.qualifiedName == wsConsumerAnnotationFqn) { // Use FQN
                logIfEnabled(project, logger<WSConsumerJavaInspection>(), "Found @${wsConsumerAnnotationFqn.substringAfterLast('.')} annotation, checkClass")
                val urlValue = annotation.findAttributeValue("url")
                val pathValue = annotation.findAttributeValue("path")
                if (urlValue == null && pathValue == null) {
                    logIfEnabled(project, logger<WSConsumerJavaInspection>(), "plugin.rules.missing.url.and.path (checkClass)")
                    return arrayOf(
                        manager.createProblemDescriptor(
                            annotation,
                            MyBundle.message("plugin.rules.missing.url.and.path", "Either 'url' or 'path' must be specified in @${wsConsumerAnnotationFqn.substringAfterLast('.')} annotation."),
                            true,
                            ProblemHighlightType.ERROR,
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
                val wsConsumerAnnotationFqn = getWsConsumerAnnotationFqn(project)

                // Check FQN for accuracy
                if (annotation.qualifiedName != wsConsumerAnnotationFqn) {
                    return
                }
                logIfEnabled(project, logger<WSConsumerJavaInspection>(), "Found @${wsConsumerAnnotationFqn.substringAfterLast('.')} annotation, visit")

                val modifierList = annotation.parent as? PsiModifierList
                val annotatedElement = modifierList?.parent as? PsiClass ?: return

                val webserviceConsumerFqn = getWebserviceConsumerFqn(project) // Get from settings
                // Use a placeholder FQN for PearlWebserviceConsumer - ADJUST THIS
                val pearlConsumerFqn = getPearlWebserviceConsumerFqn(project)
                val isPearlWebserviceConsumer = isImplementingInterface(annotatedElement, pearlConsumerFqn)

                // Extract attributes
                val urlAttr = annotation.findAttributeValue("url")
                val urlValue = if (urlAttr is PsiLiteralExpression && urlAttr.value is String) urlAttr.value as String else ""

                val pathAttr = annotation.findAttributeValue("path")
                val pathValue = if (pathAttr is PsiLiteralExpression && pathAttr.value is String) pathAttr.value as String else ""

                val sslValue = annotation.findAttributeValue("sslCertificateValidation")
                val sslCertificateValidation = if (sslValue is PsiLiteralExpression && sslValue.value is Boolean) sslValue.value as Boolean else true

                val msConsumerAttr = annotation.findAttributeValue("msConsumer")
                val hasMsConsumer = msConsumerAttr != null &&
                        msConsumerAttr.text.isNotBlank() &&
                        !msConsumerAttr.text.equals("{}") &&
                        !msConsumerAttr.text.equals("[]")
                val msConsumerValue = msConsumerAttr?.text ?: ""

                // Call common logic
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

            // isImplementingInterface helper (assuming it's defined elsewhere or copy here)
            private fun isImplementingInterface(psiClass: PsiClass, interfaceFqn: String): Boolean {
                if (interfaceFqn.isBlank()) return false
                return InheritanceUtil.isInheritor(psiClass, interfaceFqn)
            }
        }
    }
}

// --- Kotlin Implementation ---
class WSConsumerKotlinInspection : AbstractKotlinInspection(), WSConsumerInspectionLogic {
    // No need to override log here, interface provides default

    override fun getDisplayName(): String = "WSConsumer Annotation Validation (Kotlin)" // More specific display name

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {
            override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
                super.visitAnnotationEntry(annotationEntry)

                val project = annotationEntry.project
                val wsConsumerAnnotationFqn = getWsConsumerAnnotationFqn(project)

                // Basic check using short name - refine with type resolution if needed
                val shortName = annotationEntry.shortName?.asString()
                if (shortName != wsConsumerAnnotationFqn.substringAfterLast('.')) return

                // TODO: Add proper FQN check using resolved types for better accuracy
                // val resolvedFqn = annotationEntry.resolveToFQName(...)
                // if (resolvedFqn?.asString() != wsConsumerAnnotationFqn) return

                logIfEnabled(project, logger<WSConsumerKotlinInspection>(), "Found @$shortName annotation, visit")

                val ktClass: KtClass = annotationEntry.getStrictParentOfType() ?: return

                val webserviceConsumerFqn = getWebserviceConsumerFqn(project) // Get from settings
                // Basic check - refine with type resolution
                // Use a placeholder FQN for PearlWebserviceConsumer - ADJUST THIS
                val pearlConsumerShortName = "PearlWebserviceConsumer"
                val isPearlWebserviceConsumer = ktClass.superTypeListEntries.any {
                    it.typeReference?.text?.contains(pearlConsumerShortName) == true
                }

                // Helper to extract literal text
                fun getArgumentText(name: String): String {
                    val arg = annotationEntry.valueArguments.find {
                        // Use corrected access path here too
                        it.getArgumentName()?.asName?.asString() == name
                    } ?: return ""
                    val expr = arg.getArgumentExpression()
                    val text = expr?.text ?: ""
                    return if (expr is KtStringTemplateExpression && text.startsWith("\"") && text.endsWith("\"")) {
                        text.substring(1, text.length - 1)
                    } else {
                        text
                    }
                }

                val urlValue = getArgumentText("url")
                val pathValue = getArgumentText("path")
                val sslText = getArgumentText("sslCertificateValidation")
                val sslCertificateValidation = if (sslText.isEmpty()) true else sslText.equals("true", ignoreCase = true)

                val msConsumerArg = annotationEntry.valueArguments.find {
                    // Use corrected access path here too
                    it.getArgumentName()?.asName?.asString() == "msConsumer"
                }
                val msConsumerExprText = msConsumerArg?.getArgumentExpression()?.text
                val hasMsConsumer = msConsumerExprText != null &&
                        msConsumerExprText.isNotBlank() &&
                        !msConsumerExprText.matches(Regex("""\[\s*]""")) &&
                        !msConsumerExprText.matches(Regex("""\{\s*}""")) &&
                        !msConsumerExprText.equals("arrayOf()", ignoreCase = true)
                val msConsumerValue = msConsumerExprText ?: ""

                // Call common logic
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
