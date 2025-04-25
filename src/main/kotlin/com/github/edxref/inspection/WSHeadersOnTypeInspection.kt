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
import org.jetbrains.kotlin.psi.KtVisitorVoid

// --- Reusable Utilities (Consider moving to a common InspectionUtil.kt file) ---
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
// --- End Reusable Utilities ---


// --- Inspection Logic for Type Headers ---
interface WSHeadersOnTypeInspectionLogic {
    val log: Logger

    fun validateTypeHeaders(
        project: Project,
        psiClass: PsiClass,
        holder: ProblemsHolder
    ) {
        // 1. Prerequisites
        psiClass.getAnnotation(getWsConsumerAnnotationFqn(project)) ?: return
        if (!isWebserviceConsumer(psiClass)) return

        logIfEnabled(project, log, "Running WSHeadersOnType validation on ${psiClass.name}")

        // 2. Find @WSHeaders container and validate children
        val wsHeadersFqn = getWsHeadersFqn(project)
        val wsHeaderFqn = getWsHeaderFqn(project)
        val headersAnnotation = psiClass.getAnnotation(wsHeadersFqn)

        if (headersAnnotation != null) {
            val valueAttr = headersAnnotation.findAttributeValue("value")
            if (valueAttr is PsiArrayInitializerMemberValue) {
                valueAttr.initializers.forEach { initializer ->
                    if (initializer is PsiAnnotation && initializer.hasQualifiedName(wsHeaderFqn)) {
                        validateSingleTypeHeaderDefault(initializer, holder, log)
                    }
                }
            }
        }
        // Optional: Add validation for single @WSHeader on type if needed
        // val singleHeader = psiClass.getAnnotation(wsHeaderFqn)
        // if (singleHeader != null && headersAnnotation == null) { // Only if not covered by container
        //     validateSingleTypeHeaderDefault(singleHeader, holder, log)
        // }
    }

    /**
     * Validates that a single @WSHeader annotation (expected to be from the type level)
     * has a non-empty defaultValue.
     */
    private fun validateSingleTypeHeaderDefault(
        headerAnnotation: PsiAnnotation,
        holder: ProblemsHolder,
        logger: Logger // Receive logger
    ) {
        val headerName = getAnnotationStringAttribute(headerAnnotation, "name") ?: "[Unknown Name]"
        val defaultValue = getAnnotationStringAttribute(headerAnnotation, "defaultValue")
        logIfEnabled(headerAnnotation.project, logger, "Validating type @WSHeader '$headerName' with defaultValue='$defaultValue'")

        if (defaultValue.isNullOrEmpty()) { // Check for null or empty string ""
            logIfEnabled(headerAnnotation.project, logger, "ERROR: Type-level header '$headerName' has missing or empty defaultValue.")
            val defaultValueAttr = headerAnnotation.findAttributeValue("defaultValue")
            holder.registerProblem(
                defaultValueAttr ?: headerAnnotation, // Highlight defaultValue or whole annotation
                MyBundle.message("inspection.wsheadersontype.error.missing.defaultvalue", headerName),
                ProblemHighlightType.ERROR
            )
        }
    }
}

// --- Java Inspection ---
class WSHeadersOnTypeJavaInspection : AbstractBaseJavaLocalInspectionTool(), WSHeadersOnTypeInspectionLogic {
    override val log = logger<WSHeadersOnTypeJavaInspection>()
    override fun getDisplayName(): String = MyBundle.message("inspection.wsheadersontype.displayname")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitClass(psiClass: PsiClass) { // Visit classes and interfaces
                super.visitClass(psiClass)
                validateTypeHeaders(psiClass.project, psiClass, holder) // Call specific logic
            }
        }
    }
}

// --- Kotlin Inspection ---
class WSHeadersOnTypeKotlinInspection : AbstractKotlinInspection(), WSHeadersOnTypeInspectionLogic {
    override val log = logger<WSHeadersOnTypeKotlinInspection>()
    override fun getDisplayName(): String = MyBundle.message("inspection.wsheadersontype.displayname")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) { // Visit classes, objects, interfaces
                super.visitClassOrObject(classOrObject)
                val psiClass = classOrObject.toLightClass() // Get Java representation
                if (psiClass != null) {
                    validateTypeHeaders(classOrObject.project, psiClass, holder) // Call specific logic
                } else {
                    logIfEnabled(classOrObject.project, log, "Could not get LightClass for Kotlin element ${classOrObject.name}")
                }
            }
        }
    }
}
