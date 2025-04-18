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
    // Check if either FQN is valid and the class inherits from it
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
                        if (name.isNotBlank()) { // Add regardless of duplicates for validation later
                            headersMap[name] = initializer // Store the actual @WSHeader annotation
                        }
                    }
                }
            }
        }
    }

    // Check for single @WSHeader annotation
    val singleHeaderAnnotation = psiClass.getAnnotation(wsHeaderFqn)
    if (singleHeaderAnnotation != null) {
        getAnnotationStringAttribute(singleHeaderAnnotation, "name")?.let { name ->
            if (name.isNotBlank() && !headersMap.containsKey(name)) { // Add only if not already present from container
                headersMap[name] = singleHeaderAnnotation
            }
        }
    }

    return headersMap
}

/**
 * Collects @WSHeader annotations defined on a method.
 * Returns a map of header name -> PsiAnnotation (@WSHeader instance).
 */
private fun getMethodHeaders(method: PsiMethod, project: Project): Map<String, PsiAnnotation> {
    val headersMap = mutableMapOf<String, PsiAnnotation>()
    val wsHeaderFqn = getWsHeaderFqn(project)

    method.annotations.filter { it.hasQualifiedName(wsHeaderFqn) }.forEach { headerAnnotation ->
        getAnnotationStringAttribute(headerAnnotation, "name")?.let { name ->
            if (name.isNotBlank()) { // Allow multiple for now, redundancy check later
                headersMap[name] = headerAnnotation // Store the actual @WSHeader annotation
            }
        }
    }
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
        if (!isWebserviceConsumer(psiClass)) return

        logIfEnabled(project, log, "Running WSHeader validation on ${psiClass.name}")

        // 2. Get headers defined at the type level AND validate their defaultValue
        val typeHeaders = getTypeHeaders(psiClass, project)
        validateTypeHeaderDefaults(typeHeaders, holder) // NEW validation step
        logIfEnabled(project, log, "Type headers on ${psiClass.name}: ${typeHeaders.keys}")


        // 3. Iterate through methods, validate setter defaults, and check for redundancy
        for (method in psiClass.methods) { // Or psiClass.allMethods if needed
            val methodHeaders = getMethodHeaders(method, project)
            if (methodHeaders.isEmpty()) continue // Skip methods without headers

            val isSetter = method.name.startsWith("set") && method.parameterList.parametersCount == 1

            methodHeaders.forEach { (methodHeaderName, methodHeaderAnnotation) ->

                // NEW: Validate defaultValue on setters
                if (isSetter) {
                    validateSetterHeaderDefault(method, methodHeaderName, methodHeaderAnnotation, holder)
                }

                // Check for redundancy against type headers
                if (typeHeaders.containsKey(methodHeaderName)) {
                    logIfEnabled(project, log, "WARN: Redundant header '$methodHeaderName' found on method '${method.name}' also defined on type '${psiClass.name}'")
                    val nameAttrValue = methodHeaderAnnotation.findAttributeValue("name")
                    holder.registerProblem(
                        nameAttrValue ?: methodHeaderAnnotation,
                        MyBundle.message("inspection.wsheader.warn.redundant.method.header", methodHeaderName),
                        ProblemHighlightType.WARNING
                    )
                }
            }
        }
    }

    // --- New Validation Helper Methods ---

    /**
     * Validates that @WSHeader annotations defined at the type level (within @WSHeaders)
     * have a non-empty defaultValue.
     */
    private fun validateTypeHeaderDefaults(
        typeHeaders: Map<String, PsiAnnotation>,
        holder: ProblemsHolder
    ) {
        typeHeaders.forEach { (headerName, headerAnnotation) ->
            // This rule applies specifically to headers defined within @WSHeaders container
            // or potentially a single @WSHeader if that's also required.
            // Assuming the requirement is mainly for those inside @WSHeaders:
            val parentAnnotation = headerAnnotation.parent?.parent // Heuristic: @WSHeader -> PsiArrayInit -> @WSHeaders
            if (parentAnnotation is PsiAnnotation && parentAnnotation.hasQualifiedName(getWsHeadersFqn(headerAnnotation.project))) {
                val defaultValue = getAnnotationStringAttribute(headerAnnotation, "defaultValue")
                if (defaultValue.isNullOrEmpty()) { // Check for null or empty string ""
                    logIfEnabled(headerAnnotation.project, log, "ERROR: Type-level header '$headerName' has missing or empty defaultValue.")
                    val defaultValueAttr = headerAnnotation.findAttributeValue("defaultValue")
                    holder.registerProblem(
                        defaultValueAttr ?: headerAnnotation, // Highlight defaultValue or whole annotation
                        MyBundle.message("inspection.wsheader.error.missing.type.defaultvalue", headerName),
                        ProblemHighlightType.ERROR
                    )
                }
            }
            // Add similar check here if single @WSHeader on type also requires non-empty default
        }
    }

    /**
     * Validates that if a defaultValue attribute exists on a @WSHeader on a setter method,
     * it must be non-empty.
     */
    private fun validateSetterHeaderDefault(
        method: PsiMethod,
        headerName: String,
        headerAnnotation: PsiAnnotation,
        holder: ProblemsHolder
    ) {
        val defaultValueAttr = headerAnnotation.findAttributeValue("defaultValue")
        // Check if the defaultValue attribute *exists*
        if (defaultValueAttr != null) {
            // If it exists, its value must be a non-empty string
            val defaultValue = getAnnotationStringAttribute(headerAnnotation, "defaultValue")
            if (defaultValue.isNullOrEmpty()) { // Check for null or empty string ""
                logIfEnabled(method.project, log, "ERROR: Setter header '$headerName' on method '${method.name}' has an empty defaultValue.")
                holder.registerProblem(
                    defaultValueAttr, // Highlight the problematic defaultValue attribute
                    MyBundle.message("inspection.wsheader.error.invalid.setter.defaultvalue", headerName, method.name),
                    ProblemHighlightType.ERROR
                )
            }
        }
        // If defaultValueAttr is null (attribute not present), it's OK for setters.
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
        }
    }
}
