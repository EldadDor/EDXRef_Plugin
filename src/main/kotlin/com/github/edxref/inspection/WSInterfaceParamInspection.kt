package com.github.edxref.inspection

// Import settings and logger helper if you have them
// import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
// Removed incorrect PsiUtil import if it was added
import com.github.edxref.MyBundle
import com.github.edxref.settings.WSConsumerSettings
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.psi.*
import java.util.regex.Pattern

// --- Default Constants (Fallbacks) ---
private const val DEFAULT_WSCONSUMER_ANNOTATION_FQN = "com.github.edxref.annotations.WSConsumer"
private const val DEFAULT_WEBSERVICE_CONSUMER_FQN = "com.github.edxref.annotations.WebserviceConsumer"
private const val DEFAULT_WSPARAM_ANNOTATION_FQN = "com.github.edxref.annotations.WSParam"
private const val DEFAULT_PROPERTY_ANNOTATION_FQN = "com.github.edxref.annotations.Property"

// --- Logger Helper ---
private fun logIfEnabled(project: Project, logger: Logger, message: String) {
    try {
        if (project.getWSConsumerSettings().enableLog) {
            logger.info(message)
        }
    } catch (e: Exception) {
        // Ignore exceptions during logging check (e.g., settings not ready)
    }
}

// --- Settings Accessors with Fallbacks ---
private fun getSettings(project: Project): WSConsumerSettings {
    return project.getWSConsumerSettings()
}

private fun getWsConsumerAnnotationFqn(project: Project): String {
    return getSettings(project).wsConsumerAnnotationFqn.ifBlank { DEFAULT_WSCONSUMER_ANNOTATION_FQN }
}

private fun getWebserviceConsumerFqn(project: Project): String {
    return getSettings(project).webserviceConsumerFqn.ifBlank { DEFAULT_WEBSERVICE_CONSUMER_FQN }
}

private fun getWsParamFqn(project: Project): String {
    return getSettings(project).wsParamAnnotationFqn.ifBlank { DEFAULT_WSPARAM_ANNOTATION_FQN }
}

private fun getPropertyFqn(project: Project): String {
    return getSettings(project).propertyAnnotationFqn.ifBlank { DEFAULT_PROPERTY_ANNOTATION_FQN }
}

// --- Helper Functions ---

/**
 * Extracts parameter names (like "userId") from placeholders like "@userId" in a URL.
 */
private fun extractUrlParameters(url: String?): Set<String> {
    if (url.isNullOrBlank()) {
        return emptySet()
    }
    val params = mutableSetOf<String>()
    val pattern = Pattern.compile("@(\\w+)")
    val matcher = pattern.matcher(url)
    while (matcher.find()) {
        params.add(matcher.group(1))
    }
    return params
}

/**
 * Gets the effective parameter name from a setter method using configurable FQN.
 */
private fun getEffectiveParamName(method: PsiMethod): String? {
    if (!method.name.startsWith("set") || method.parameterList.parametersCount != 1) {
        return null
    }
    val project = method.project
    val wsParamAnnotation = method.getAnnotation(getWsParamFqn(project))
    if (wsParamAnnotation != null) {
        val nameAttrValue = wsParamAnnotation.findAttributeValue("name")
        val explicitName = if (nameAttrValue is PsiLiteralExpression && nameAttrValue.value is String) {
            nameAttrValue.value as String
        } else {
            null
        }
        if (!explicitName.isNullOrBlank()) {
            return explicitName
        }
    }
    if (method.name.length > 3) {
        return method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
    }
    return null
}

/**
 * Checks if a class/interface inherits from a specific fully qualified name.
 */
private fun isImplementingInterface(psiClass: PsiClass, interfaceFqn: String): Boolean {
    if (interfaceFqn.isBlank()) return false
    return InheritanceUtil.isInheritor(psiClass, interfaceFqn)
}

// --- Inspection Logic Interface ---

interface WSInterfaceParamInspectionLogic {
    val log: Logger // Logger instance for the specific inspection implementation

    // --- Main Validation Entry Point ---
    fun validateInterfaceParams(
        project: Project,
        psiClass: PsiClass,
        holder: ProblemsHolder
    ) {
        // 1. Check prerequisites (is interface, has @WSConsumer, implements WebserviceConsumer)
        val wsConsumerAnnotation = checkPrerequisites(project, psiClass) ?: return

        // 2. Extract URL and parameters
        val (urlValue, urlAttrValue) = getUrlValue(wsConsumerAnnotation)
        val urlParams = extractUrlParameters(urlValue)
        logIfEnabled(project, log, "URL Params for ${psiClass.name}: $urlParams")

        // 3. Process interface methods (check @Property, map setters)
        val (methodParamMap, methodsWithExplicitWsParamName) = processInterfaceMethods(project, psiClass, holder)
        logIfEnabled(project, log, "Effective Method Params for ${psiClass.name}: ${methodParamMap.keys}")

        // 4. Validate HTTP method and associated getter rules (isBodyParam)
        // Pass wsConsumerAnnotation down for context
        validateHttpMethodAndGetters(project, wsConsumerAnnotation, psiClass, holder)

        // 5. Validate URL parameters against the processed setters
        validateUrlParamsAgainstSetters(project, urlParams, urlAttrValue, methodParamMap, methodsWithExplicitWsParamName, wsConsumerAnnotation, holder)
    }

    // --- Private Helper Methods within the Interface ---

    /**
     * Checks if the class is an interface, has @WSConsumer, and implements WebserviceConsumer.
     * Returns the @WSConsumer annotation if all checks pass, null otherwise.
     */
    private fun checkPrerequisites(project: Project, psiClass: PsiClass): PsiAnnotation? {
        if (!psiClass.isInterface) return null

        val wsConsumerAnnotationFqn = getWsConsumerAnnotationFqn(project)
        val wsConsumerAnnotation = psiClass.getAnnotation(wsConsumerAnnotationFqn)
        if (wsConsumerAnnotation == null) {
            logIfEnabled(project, log, "Interface ${psiClass.name} lacks @${wsConsumerAnnotationFqn.substringAfterLast('.')}")
            return null
        }
        logIfEnabled(project, log, "Found @${wsConsumerAnnotationFqn.substringAfterLast('.')} on interface ${psiClass.name}")

        val webserviceConsumerFqn = getWebserviceConsumerFqn(project)
        if (!isImplementingInterface(psiClass, webserviceConsumerFqn)) {
            logIfEnabled(project, log, "Interface ${psiClass.name} does not implement $webserviceConsumerFqn")
            return null
        }
        logIfEnabled(project, log, "Interface ${psiClass.name} implements $webserviceConsumerFqn")

        return wsConsumerAnnotation
    }

    /**
     * Extracts the URL string value and the corresponding attribute element from the @WSConsumer annotation.
     */
    private fun getUrlValue(wsConsumerAnnotation: PsiAnnotation): Pair<String?, PsiAnnotationMemberValue?> {
        val urlAttrValue = wsConsumerAnnotation.findAttributeValue("url")
        val urlValue = if (urlAttrValue is PsiLiteralExpression && urlAttrValue.value is String) {
            urlAttrValue.value as String
        } else {
            null
        }
        return Pair(urlValue, urlAttrValue)
    }

    /**
     * Processes methods in the interface: checks @Property on setters, maps effective parameter names.
     * Returns a pair containing the methodParamMap and methodsWithExplicitWsParamName map.
     */
    private fun processInterfaceMethods(
        project: Project,
        psiClass: PsiClass,
        holder: ProblemsHolder
    ): Pair<Map<String, List<PsiMethod>>, Map<PsiMethod, String>> {

        val methodParamMap = mutableMapOf<String, MutableList<PsiMethod>>()
        val methodsWithExplicitWsParamName = mutableMapOf<PsiMethod, String>()
        val propertyAnnotationFqn = getPropertyFqn(project)
        val propertyAnnotationShortName = propertyAnnotationFqn.substringAfterLast('.')
        val wsParamFqn = getWsParamFqn(project)

        // Check if validation for @Property annotations is enabled
        val validatePropertyAnnotations = getSettings(project).validatePropertyAnnotations

        for (method in psiClass.methods) {
            // Check if it looks like a setter or getter
            val isSetter = method.name.startsWith("set") && method.parameterList.parametersCount == 1
            val isGetter = method.name.startsWith("get") && method.parameterList.parametersCount == 0

            if (isSetter || isGetter) {
                // Validate @Property annotation if enabled
                if (validatePropertyAnnotations && method.getAnnotation(propertyAnnotationFqn) == null) {
                    logIfEnabled(project, log, "ERROR: Method '${method.name}' is missing the @$propertyAnnotationShortName annotation.")
                    holder.registerProblem(
                        method.nameIdentifier ?: method,
                        MyBundle.message("inspection.wsinterfaceparam.error.missing.property.annotation", method.name, propertyAnnotationShortName),
                        ProblemHighlightType.ERROR
                    )
                }
            }

            // Process setters for parameter mapping
            if (isSetter) {
                val effectiveName = getEffectiveParamName(method)
                if (effectiveName != null) {
                    methodParamMap.computeIfAbsent(effectiveName) { mutableListOf() }.add(method)

                    // Store methods with explicit @WSParam names
                    val wsParamAnnotation = method.getAnnotation(wsParamFqn)
                    if (wsParamAnnotation != null) {
                        val nameAttrValue = wsParamAnnotation.findAttributeValue("name")
                        val explicitName = if (nameAttrValue is PsiLiteralExpression && nameAttrValue.value is String) {
                            nameAttrValue.value as String
                        } else { null }
                        if (!explicitName.isNullOrBlank()) {
                            methodsWithExplicitWsParamName[method] = explicitName
                        }
                    }
                }
            }
        }
        return Pair(methodParamMap, methodsWithExplicitWsParamName)
    }

    /**
     * Validates rules related to the HTTP method (GET/POST/PUT) and getter annotations.
     * Requires wsConsumerAnnotation to determine the method and for highlighting.
     */
    private fun validateHttpMethodAndGetters(
        project: Project,
        wsConsumerAnnotation: PsiAnnotation, // Pass the annotation
        psiClass: PsiClass,
        holder: ProblemsHolder
    ) {
        val methodAttrValue = wsConsumerAnnotation.findAttributeValue("method")
        val methodValue = if (methodAttrValue is PsiReferenceExpression) {
            methodAttrValue.referenceName // e.g., GET, POST, PUT
        } else {
            null
        }

        if (methodValue in listOf("GET", "POST", "PUT")) {
            logIfEnabled(project, log, "Method is $methodValue for ${psiClass.name}, validating getters.")
            // Pass wsConsumerAnnotation down for highlighting context
            validateGettersForBodyParam(project, psiClass, wsConsumerAnnotation, holder)
        }
    }

    /**
     * Validates that only one getter is marked with isBodyParam=true when required.
     * Requires wsConsumerAnnotation for highlighting context.
     */
    /**
     * Validates getter rules for GET/POST/PUT methods.
     * If getters exist, exactly one must be marked with isBodyParam=true.
     * Requires wsConsumerAnnotation for highlighting context.
     */
    private fun validateGettersForBodyParam(
        project: Project,
        psiClass: PsiClass,
        wsConsumerAnnotation: PsiAnnotation, // Receive the annotation for context
        holder: ProblemsHolder
    ) {
        val wsParamFqn = getWsParamFqn(project)
        // Find all methods starting with "get" and having no parameters
        val getters = psiClass.methods.filter { it.name.startsWith("get") && it.parameterList.parametersCount == 0 }

        // Only apply the rule if getters actually exist in the interface
        if (getters.isNotEmpty()) {
            // Find which of the existing getters are marked as the body parameter
            val bodyParamGetters = getters.filter { method ->
                val wsParamAnnotation = method.getAnnotation(wsParamFqn)
                val isBodyParamAttr = wsParamAnnotation?.findAttributeValue("isBodyParam")
                // Check for literal `true` or text "true"
                (isBodyParamAttr is PsiLiteralExpression && isBodyParamAttr.value == true) || isBodyParamAttr?.text == "true"
            }

            // Determine the element to highlight for class-level errors (missing/multiple)
            // Prefer highlighting the 'method' attribute if possible
            val highlightElementForClassError = wsConsumerAnnotation.findAttributeValue("method") ?: psiClass.nameIdentifier ?: psiClass

            when (bodyParamGetters.size) {
                0 -> {
                    // Rule broken: Getters exist, but none are marked as body param
                    logIfEnabled(project, log, "ERROR: Getters exist but none marked with isBodyParam=true in ${psiClass.name}")
                    holder.registerProblem(
                        highlightElementForClassError, // Highlight method attribute or class
                        MyBundle.message("inspection.wsinterfaceparam.error.bodyparam.required"),
                        ProblemHighlightType.ERROR
                    )
                }
                1 -> {
                    // Correct case: Exactly one getter marked.
                    logIfEnabled(project, log, "OK: Found exactly one getter marked with isBodyParam=true in ${psiClass.name}")
                    // No problem to report.
                }
                else -> { // More than 1
                    // Rule broken: Too many getters marked as body param
                    logIfEnabled(project, log, "ERROR: Multiple getters marked with isBodyParam=true in ${psiClass.name}")
                    holder.registerProblem(
                        highlightElementForClassError, // Highlight method attribute or class
                        MyBundle.message("inspection.wsinterfaceparam.error.multiple.bodyparam"),
                        ProblemHighlightType.ERROR
                    )
                    // Additionally, highlight each offending getter
                    bodyParamGetters.forEach { getter ->
                        holder.registerProblem(
                            getter.nameIdentifier ?: getter, // Highlight the getter name
                            MyBundle.message("inspection.wsinterfaceparam.error.multiple.bodyparam"), // Can reuse message or make specific
                            ProblemHighlightType.ERROR
                        )
                    }
                }
            }
        } else {
            // No getters exist in the interface, which is valid for GET/POST/PUT (no body expected from interface definition)
            logIfEnabled(project, log, "OK: No getters found in ${psiClass.name}, skipping isBodyParam check.")
        }
    }


    /**
     * Performs validation rules comparing URL parameters against setter methods.
     */
    private fun validateUrlParamsAgainstSetters(
        project: Project,
        urlParams: Set<String>,
        urlAttrValue: PsiAnnotationMemberValue?,
        methodParamMap: Map<String, List<PsiMethod>>,
        methodsWithExplicitWsParamName: Map<PsiMethod, String>,
        wsConsumerAnnotation: PsiAnnotation,
        holder: ProblemsHolder
    ) {
        if (urlParams.isEmpty()) {
            logIfEnabled(project, log, "Skipping URL parameter validation as none were found.")
            return
        }

        // Rule 1: Check if all URL params have a corresponding setter
        val foundMethodParams = methodParamMap.keys
        val missingParams = urlParams - foundMethodParams
        if (missingParams.isNotEmpty()) {
            missingParams.forEach { missingParam ->
                logIfEnabled(project, log, "ERROR: Missing setter for URL param '@$missingParam'")
                val highlightElement = urlAttrValue ?: wsConsumerAnnotation.nameReferenceElement ?: wsConsumerAnnotation
                holder.registerProblem(
                    highlightElement,
                    MyBundle.message("inspection.wsinterfaceparam.error.missing.setter", missingParam),
                    ProblemHighlightType.ERROR
                )
            }
        }

        // Iterate through methods mapped to effective param names
        methodParamMap.forEach { (effectiveParamName, methods) ->
            methods.forEach { method ->
                // Rule 2: Check for setter name mismatch (WARN)
                val derivedFromName = method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
                if (effectiveParamName != derivedFromName && method in methodsWithExplicitWsParamName) {
                    logIfEnabled(project, log, "WARN: Setter name '${method.name}' differs from effective param '$effectiveParamName' defined by @WSParam.")
                    holder.registerProblem(
                        method.nameIdentifier ?: method,
                        MyBundle.message("inspection.wsinterfaceparam.warn.setter.name.mismatch", method.name, effectiveParamName),
                        ProblemHighlightType.WARNING
                    )
                }

                // Rule 3: Check if explicit @WSParam name matches a URL param (ERROR)
                val explicitWsParamName = methodsWithExplicitWsParamName[method]
                if (explicitWsParamName != null && explicitWsParamName !in urlParams) {
                    logIfEnabled(project, log, "ERROR: Explicit @WSParam name '$explicitWsParamName' not in URL params $urlParams")
                    val wsParamNameValueElement = method.getAnnotation(getWsParamFqn(project))?.findAttributeValue("name")
                    holder.registerProblem(
                        wsParamNameValueElement ?: method.getAnnotation(getWsParamFqn(project)) ?: method.nameIdentifier ?: method,
                        MyBundle.message("inspection.wsinterfaceparam.error.wsparam.name.mismatch", explicitWsParamName, urlParams.joinToString()),
                        ProblemHighlightType.ERROR
                    )
                }
            }
        }
    }
}

// --- Java Inspection ---
class WSInterfaceParamJavaInspection : AbstractBaseJavaLocalInspectionTool(), WSInterfaceParamInspectionLogic {
    override val log = logger<WSInterfaceParamJavaInspection>()
    override fun getDisplayName(): String = MyBundle.message("inspection.wsinterfaceparam.displayname")
    override fun getStaticDescription(): String = MyBundle.message("inspection.wsinterfaceparam.description")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitClass(psiClass: PsiClass) {
                super.visitClass(psiClass)
                // Call the main validation method from the interface
                validateInterfaceParams(psiClass.project, psiClass, holder)
            }
        }
    }
}

// --- Kotlin Inspection ---
class WSInterfaceParamKotlinInspection : AbstractKotlinInspection(), WSInterfaceParamInspectionLogic {
    override val log = logger<WSInterfaceParamKotlinInspection>()
    override fun getDisplayName(): String = MyBundle.message("inspection.wsinterfaceparam.displayname")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                super.visitClassOrObject(classOrObject)
                if (classOrObject is KtClass && classOrObject.isInterface()) {
                    val psiClass = classOrObject.toLightClass()
                    if (psiClass != null) {
                        // Call the main validation method from the interface
                        validateInterfaceParams(classOrObject.project, psiClass, holder)
                    } else {
                        logIfEnabled(classOrObject.project, log, "Could not get LightClass for Kotlin interface ${classOrObject.name}")
                    }
                }
            }
        }
    }
}
