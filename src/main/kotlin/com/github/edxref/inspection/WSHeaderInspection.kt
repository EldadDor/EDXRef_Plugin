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
import org.jetbrains.kotlin.psi.*

// --- Settings Accessors (Copied or move to Util) ---
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

// --- Helper Functions ---

/**
 * Gets the string value of an annotation attribute, assuming it's a literal.
 */
private fun getAnnotationStringAttribute(annotation: PsiAnnotation, attributeName: String): String? {
    val attrValue = annotation.findAttributeValue(attributeName)
    return if (attrValue is PsiLiteralExpression && attrValue.value is String) {
        attrValue.value as String
    } else {
        null
    }
}

/**
 * Collects all @WSHeader annotations defined directly on a type, handling @WSHeaders container.
 * Returns a map of header name -> PsiAnnotation (@WSHeader instance).
 */
private fun getTypeHeaders(psiClass: PsiClass, project: Project): Map<String, PsiAnnotation> {
    val headersMap = mutableMapOf<String, PsiAnnotation>()
    val wsHeaderFqn = getWsHeaderFqn(project)
    val wsHeadersFqn = getWsHeadersFqn(project)

    // Check for container annotation @WSHeaders
    val headersAnnotation = psiClass.getAnnotation(wsHeadersFqn)
    if (headersAnnotation != null) {
        val valueAttr = headersAnnotation.findAttributeValue("value")
        if (valueAttr is PsiArrayInitializerMemberValue) {
            valueAttr.initializers.forEach { initializer ->
                if (initializer is PsiAnnotation && initializer.hasQualifiedName(wsHeaderFqn)) {
                    getAnnotationStringAttribute(initializer, "name")?.let { name ->
                        if (name.isNotBlank() && !headersMap.containsKey(name)) { // Add only if name is valid and not already added
                            headersMap[name] = initializer
                        }
                    }
                }
            }
        }
    }

    // Check for single @WSHeader annotation (only if not already added via container)
    val singleHeaderAnnotation = psiClass.getAnnotation(wsHeaderFqn)
    if (singleHeaderAnnotation != null) {
        getAnnotationStringAttribute(singleHeaderAnnotation, "name")?.let { name ->
            if (name.isNotBlank() && !headersMap.containsKey(name)) {
                headersMap[name] = singleHeaderAnnotation
            }
        }
    }

    return headersMap
}

/**
 * Collects @WSHeader annotations defined on a method.
 * Returns a map of header name -> PsiAnnotation (@WSHeader instance).
 * Note: Assumes only one @WSHeader per method is typical, but handles multiple defensively.
 */
private fun getMethodHeaders(method: PsiMethod, project: Project): Map<String, PsiAnnotation> {
    val headersMap = mutableMapOf<String, PsiAnnotation>()
    val wsHeaderFqn = getWsHeaderFqn(project)

    // In Java, annotations are directly on the method
    method.annotations.filter { it.hasQualifiedName(wsHeaderFqn) }.forEach { headerAnnotation ->
        getAnnotationStringAttribute(headerAnnotation, "name")?.let { name ->
            if (name.isNotBlank() && !headersMap.containsKey(name)) { // Avoid duplicates on the same method if somehow present
                headersMap[name] = headerAnnotation
            }
        }
    }
    // Note: Kotlin might require checking KtPropertyAccessor if annotation is on getter/setter specifically
    return headersMap
}


// --- Inspection Logic ---
interface WSHeaderInspectionLogic {
    val log: Logger

    fun validateHeaders(
        project: Project,
        psiClass: PsiClass, // Common ground: Java class or Kotlin Light Class
        holder: ProblemsHolder
    ) {
        // 1. Prerequisites: Check for @WSConsumer and consumer type
        val wsConsumerAnnotation = psiClass.getAnnotation(getWsConsumerAnnotationFqn(project)) ?: return
        if (!isWebserviceConsumer(psiClass)) return // Ensure it's a relevant consumer type

        logIfEnabled(project, log, "Running WSHeader validation on ${psiClass.name}")

        // 2. Get headers defined at the type level
        val typeHeaders = getTypeHeaders(psiClass, project)
        if (typeHeaders.isEmpty()) {
            logIfEnabled(project, log, "No type-level headers found on ${psiClass.name}")
            // No type headers, so no redundancy possible. We can potentially stop here
            // unless there are other method-only header rules in the future.
            return
        }
        logIfEnabled(project, log, "Type headers on ${psiClass.name}: ${typeHeaders.keys}")

        // 3. Iterate through methods and check for redundant headers
        // Use allMethods to check inherited methods too, if applicable. Use methods for only direct declarations.
        for (method in psiClass.methods) { // Or psiClass.allMethods
            val methodHeaders = getMethodHeaders(method, project)

            methodHeaders.forEach { (methodHeaderName, methodHeaderAnnotation) ->
                // Check if this header name is also defined at the type level
                if (typeHeaders.containsKey(methodHeaderName)) {
                    logIfEnabled(project, log, "WARN: Redundant header '$methodHeaderName' found on method '${method.name}' also defined on type '${psiClass.name}'")

                    // Report warning on the method's @WSHeader annotation or its name attribute
                    val nameAttrValue = methodHeaderAnnotation.findAttributeValue("name")
                    holder.registerProblem(
                        nameAttrValue ?: methodHeaderAnnotation, // Highlight name value or whole annotation
                        MyBundle.message("inspection.wsheader.warn.redundant.method.header", methodHeaderName),
                        ProblemHighlightType.WARNING // Severity WARN
                    )
                }
            }
        }
    }
}

// --- Java Inspection ---
class WSHeaderJavaInspection : AbstractBaseJavaLocalInspectionTool(), WSHeaderInspectionLogic {
    override val log = logger<WSHeaderJavaInspection>()
    override fun getDisplayName(): String = MyBundle.message("inspection.wsheader.displayname")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitClass(psiClass: PsiClass) { // Visit classes and interfaces
                super.visitClass(psiClass)
                validateHeaders(psiClass.project, psiClass, holder) // Call shared logic
            }
        }
    }
}

// --- Kotlin Inspection ---
class WSHeaderKotlinInspection : AbstractKotlinInspection(), WSHeaderInspectionLogic {
    override val log = logger<WSHeaderKotlinInspection>()
    override fun getDisplayName(): String = MyBundle.message("inspection.wsheader.displayname")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) { // Visit classes, objects, interfaces
                super.visitClassOrObject(classOrObject)
                val psiClass = classOrObject.toLightClass() // Get Java representation
                if (psiClass != null) {
                    validateHeaders(classOrObject.project, psiClass, holder) // Call shared logic
                } else {
                    logIfEnabled(classOrObject.project, log, "Could not get LightClass for Kotlin element ${classOrObject.name}")
                }
            }
            // Note: For Kotlin, annotations might be on KtProperty or KtPropertyAccessor.
            // The current logic using light classes checks the generated Java methods.
            // If annotations are *only* on Kotlin properties/accessors and don't generate
            // corresponding Java method annotations, this might need adjustment to visit
            // KtProperty/KtPropertyAccessor directly. Test with your specific annotation usage.
        }
    }
}
