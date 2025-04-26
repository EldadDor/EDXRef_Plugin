package com.github.edxref.inspection

import com.github.edxref.MyBundle
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.codeInspection.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

// --- Reusable Utilities (Copied or move to Util) ---
private fun getSettings(project: Project) = project.getWSConsumerSettings()
private fun getWsConsumerAnnotationFqn(project: Project) = getSettings(project).wsConsumerAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSConsumer" }
private fun getWebserviceConsumerFqn(project: Project) = getSettings(project).webserviceConsumerFqn.ifBlank { "com.github.edxref.annotations.WebserviceConsumer" }
private fun getPearlWebserviceConsumerFqn(project: Project) = getSettings(project).pearlWebserviceConsumerFqn.ifBlank { "com.github.edxref.annotations.PearlWebserviceConsumer" }
private fun getWsHeaderFqn(project: Project) = getSettings(project).wsHeaderAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSHeader" }
private fun getWsHeadersFqn(project: Project) = getSettings(project).wsHeadersAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSHeaders" }
private fun logIfEnabled(project: Project, logger: Logger, message: String) {
    try {
        if (getSettings(project).enableLog) logger.info(message)
    } catch (e: Exception) { /* ignore */
    }
}

private fun isWebserviceConsumer(psiClass: PsiClass): Boolean {
    val project = psiClass.project
    val wsConsumerFqn = getWebserviceConsumerFqn(project)
    val pearlConsumerFqn = getPearlWebserviceConsumerFqn(project)
    return (wsConsumerFqn.isNotBlank() && InheritanceUtil.isInheritor(psiClass, wsConsumerFqn)) ||
            (pearlConsumerFqn.isNotBlank() && InheritanceUtil.isInheritor(psiClass, pearlConsumerFqn))
}

private fun getAnnotationStringAttribute(annotation: PsiAnnotation, attributeName: String): String? {
    val attrValue = annotation.findAttributeValue(attributeName)
    return if (attrValue is PsiLiteralExpression && attrValue.value is String) attrValue.value as String else null
}

// Helper to get type headers (needed for redundancy check)
private fun getTypeHeadersMap(psiClass: PsiClass, project: Project): Map<String, PsiAnnotation> {
    val headersMap = mutableMapOf<String, PsiAnnotation>()
    val wsHeaderFqn = getWsHeaderFqn(project)
    val wsHeadersFqn = getWsHeadersFqn(project)
    val headersAnnotation = psiClass.getAnnotation(wsHeadersFqn)
    if (headersAnnotation != null) {
        val valueAttr = headersAnnotation.findAttributeValue("value")
        if (valueAttr is PsiArrayInitializerMemberValue) {
            valueAttr.initializers.forEach { initializer ->
                if (initializer is PsiAnnotation && initializer.hasQualifiedName(wsHeaderFqn)) {
                    getAnnotationStringAttribute(initializer, "name")?.let { name ->
                        if (name.isNotBlank()) headersMap[name] = initializer
                    }
                }
            }
        }
    }
    val singleHeaderAnnotation = psiClass.getAnnotation(wsHeaderFqn)
    if (singleHeaderAnnotation != null) {
        getAnnotationStringAttribute(singleHeaderAnnotation, "name")?.let { name ->
            if (name.isNotBlank() && !headersMap.containsKey(name)) headersMap[name] = singleHeaderAnnotation
        }
    }
    return headersMap
}
// --- End Reusable Utilities ---


// --- Inspection Logic for Method Headers ---
interface WSHeaderOnMethodInspectionLogic {
    val log: Logger

    fun validateMethodHeader(
        project: Project,
        method: PsiMethod,
        holder: ProblemsHolder
    ) {
        // 1. Prerequisites for the containing class
        val containingClass = method.containingClass ?: return
        containingClass.getAnnotation(getWsConsumerAnnotationFqn(project)) ?: return
        if (!isWebserviceConsumer(containingClass)) return

        logIfEnabled(project, log, "Running WSHeaderOnMethod validation on ${containingClass.name}.${method.name}")

        // 2. Get headers on the current method
        val methodHeaders = getMethodHeaders(method, project) // Use existing helper
        if (methodHeaders.isEmpty()) return // Nothing to validate on this method

        // 3. Get type headers for redundancy check
        val typeHeaders = getTypeHeadersMap(containingClass, project) // Use helper

        // 4. Validate each header on the method
        val isSetter = method.name.startsWith("set") && method.parameterList.parametersCount == 1

        methodHeaders.forEach { (methodHeaderName, methodHeaderAnnotation) ->
            // Rule 1: Validate defaultValue on setters
            if (isSetter) {
                validateSetterHeaderDefault(method, methodHeaderName, methodHeaderAnnotation, holder, log)
            }

            // Rule 2: Check for redundancy against type headers
            if (typeHeaders.containsKey(methodHeaderName)) {
                logIfEnabled(project, log, "WARN: Redundant header '$methodHeaderName' on method '${method.name}' also defined on type '${containingClass.name}'")
                val nameAttrValue = methodHeaderAnnotation.findAttributeValue("name")
                holder.registerProblem(
                    nameAttrValue ?: methodHeaderAnnotation,
                    MyBundle.message("inspection.wsheaderonmethod.warn.redundant.method.header", methodHeaderName),
                    ProblemHighlightType.WARNING
                )
            }
        }
    }

    /**
     * Validates that if a defaultValue attribute exists on a @WSHeader on a setter method,
     * it must be non-empty. (Copied from previous logic)
     */
    private fun validateSetterHeaderDefault(
        method: PsiMethod,
        headerName: String,
        headerAnnotation: PsiAnnotation,
        holder: ProblemsHolder,
        logger: Logger
    ) {
        val defaultValueAttr = headerAnnotation.findAttributeValue("defaultValue")
        if (defaultValueAttr != null) {
            val isValid = when (defaultValueAttr) {
                is PsiLiteralExpression -> (defaultValueAttr.value as? String)?.isNotEmpty() == true
                is PsiReferenceExpression -> true // Accept constant reference
                else -> false
            }
            logIfEnabled(method.project, logger, "Validating @WSHeader '$headerName' on setter '${method.name}' with defaultValueAttr='$defaultValueAttr'")
            if (!isValid) {
                logIfEnabled(method.project, logger, "ERROR: Setter header '$headerName' on method '${method.name}' has an empty or invalid defaultValue.")

                // Highlight the method itself
                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    MyBundle.message("inspection.wsheaderonmethod.error.invalid.setter.defaultvalue", headerName, method.name),
                    ProblemHighlightType.ERROR
                )

                // Also highlight the class/interface declaration
                val classOrInterface = method.containingClass
                val classElementToHighlight = classOrInterface?.nameIdentifier ?: classOrInterface
                if (classElementToHighlight != null) {
                    holder.registerProblem(
                        classElementToHighlight,
                        MyBundle.message("inspection.wsheaderonmethod.error.invalid.setter.defaultvalue", headerName, method.name),
                        ProblemHighlightType.ERROR
                    )
                }
            }
        }
    }


    // Helper to get method headers (copied from previous logic)
    private fun getMethodHeaders(method: PsiMethod, project: Project): Map<String, PsiAnnotation> {
        val headersMap = mutableMapOf<String, PsiAnnotation>()
        val wsHeaderFqn = getWsHeaderFqn(project)
        method.annotations.filter { it.hasQualifiedName(wsHeaderFqn) }.forEach { headerAnnotation ->
            getAnnotationStringAttribute(headerAnnotation, "name")?.let { name ->
                if (name.isNotBlank()) headersMap[name] = headerAnnotation
            }
        }
        return headersMap
    }
}

// --- Java Inspection ---
class WSHeaderOnMethodJavaInspection : AbstractBaseJavaLocalInspectionTool(), WSHeaderOnMethodInspectionLogic {
    override val log = logger<WSHeaderOnMethodJavaInspection>()
    override fun getDisplayName(): String = MyBundle.message("inspection.wsheaderonmethod.displayname")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) { // Visit methods directly
                super.visitMethod(method)
                validateMethodHeader(method.project, method, holder) // Call specific logic
            }
        }
    }
}

// --- Kotlin Inspection ---
class WSHeaderOnMethodKotlinInspection : AbstractKotlinInspection(), WSHeaderOnMethodInspectionLogic {
    override val log = logger<WSHeaderOnMethodKotlinInspection>()
    override fun getDisplayName(): String = MyBundle.message("inspection.wsheaderonmethod.displayname")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) { // Visit functions directly
                super.visitNamedFunction(function)
                // Try to get the corresponding PsiMethod (light method)
                // Note: This might be complex if annotations are only on accessors.
                // A simpler approach for Kotlin might be needed if light methods don't work well.
                // For now, assuming light methods work for annotation checking:
                val containingClassOrObject = function.containingClassOrObject
                val containingLightClass = containingClassOrObject?.toLightClass()
                if (containingLightClass != null) {
                    // Find the corresponding PsiMethod in the light class
                    val psiMethod = containingLightClass.findMethodsByName(function.name, false)
                        .firstOrNull { it.parameterList.parametersCount == function.valueParameters.size } // Basic matching
                    if (psiMethod != null) {
                        validateMethodHeader(function.project, psiMethod, holder) // Call specific logic
                    } else {
                        logIfEnabled(function.project, log, "Could not find matching PsiMethod for Kotlin function ${function.name}")
                    }
                } else {
                    logIfEnabled(function.project, log, "Could not get LightClass for containing element of ${function.name}")
                }
            }
        }
    }
}
