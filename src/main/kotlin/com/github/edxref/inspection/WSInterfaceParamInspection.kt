package com.github.edxref.inspection

// Import settings and logger helper if you have them
// import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
// Removed incorrect PsiUtil import if it was added
import com.github.edxref.MyBundle
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

// --- Constants ---
private const val WSCONSUMER_ANNOTATION_FQN = "com.github.edxref.annotations.WSConsumer" // ADJUST PACKAGE if needed
private const val WEBSERVICE_CONSUMER_FQN = "com.github.edxref.annotations.WebserviceConsumer" // ADJUST PACKAGE if needed
private const val WSPARAM_ANNOTATION_FQN = "com.github.edxref.annotations.WSParam" // ADJUST PACKAGE if needed
private const val PROPERTY_ANNOTATION_FQN = "com.github.edxref.annotations.Property" // ADJUST PACKAGE if needed

// --- Logger Helper (assuming you have one) ---
private fun logIfEnabled(project: Project, logger: Logger, message: String) {
    // Replace with your actual implementation if using settings
    logger.info(message) // Simple logging for now
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
    // Regex to find @ followed by word characters
    val pattern = Pattern.compile("@(\\w+)")
    val matcher = pattern.matcher(url)
    while (matcher.find()) {
        params.add(matcher.group(1)) // Add the group captured (the name without @)
    }
    return params
}

/**
 * Gets the effective parameter name from a setter method.
 * Uses @WSParam(name=...) if present and non-empty, otherwise derives from the setter name.
 * Returns null if it's not a valid setter or cannot determine a name.
 */
private fun getEffectiveParamName(method: PsiMethod): String? {
    if (!method.name.startsWith("set") || method.parameterList.parametersCount != 1) {
        return null // Not a standard setter
    }

    val wsParamAnnotation = method.getAnnotation(WSPARAM_ANNOTATION_FQN)
    if (wsParamAnnotation != null) {
        // --- CORRECTED WAY TO GET STRING ATTRIBUTE VALUE ---
        val nameAttrValue = wsParamAnnotation.findAttributeValue("name")
        val explicitName = if (nameAttrValue is PsiLiteralExpression && nameAttrValue.value is String) {
            nameAttrValue.value as String
        } else {
            null // Not a string literal or not found
        }
        // --- END CORRECTION ---

        if (!explicitName.isNullOrBlank()) {
            return explicitName // Explicit name takes precedence
        }
    }

    // Derive name from setter: setUserId -> userId
    if (method.name.length > 3) {
        return method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
    }
    return null
}

// Kotlin version
private fun getEffectiveParamName(function: KtNamedFunction): String? {
    if (!function.nameIdentifier?.text?.startsWith("set")!! || function.valueParameters.size != 1) {
        return null // Not a standard setter
    }
    val functionName = function.nameIdentifier?.text ?: return null

    val wsParamAnnotation = function.annotationEntries.find {
        // Basic check, refine with type resolution if needed
        it.shortName?.asString() == "WSParam"
    }

    if (wsParamAnnotation != null) {
        val nameArgument = wsParamAnnotation.valueArguments.find { it.getArgumentName()?.asName?.asString() == "name" }
        val explicitNameExpr = nameArgument?.getArgumentExpression()
        // Check if it's a string template (covers simple strings)
        if (explicitNameExpr is KtStringTemplateExpression) {
            // Attempt to get the simple content if no interpolation
            val explicitName = explicitNameExpr.entries
                .filterIsInstance<KtLiteralStringTemplateEntry>()
                .joinToString("") { it.text } // Get text content of literal parts

            if (explicitName.isNotBlank()) {
                return explicitName // Explicit name takes precedence
            }
        }
        // TODO: Handle constant references if needed
    }

    // Derive name from setter: setUserId -> userId
    if (functionName.length > 3) {
        return functionName.substring(3).replaceFirstChar { it.lowercaseChar() }
    }
    return null
}


/**
 * Checks if a class/interface inherits from a specific fully qualified name.
 */
private fun isImplementingInterface(psiClass: PsiClass, interfaceFqn: String): Boolean {
    return InheritanceUtil.isInheritor(psiClass, interfaceFqn)
}

// Kotlin version (basic PSI check, might need refinement with type resolution)
private fun isImplementingInterface(ktClass: KtClass, interfaceFqn: String): Boolean {
    // Check using KtLightClass for better Java interop/hierarchy check
    val lightClass = ktClass.toLightClass()
    if (lightClass != null) {
        return InheritanceUtil.isInheritor(lightClass, interfaceFqn)
    }

    // Fallback to basic PSI check (less reliable for complex hierarchies)
    val shortName = interfaceFqn.substringAfterLast('.')
    fun checkHierarchy(currentClass: KtClass?): Boolean {
        if (currentClass == null) return false
        currentClass.superTypeListEntries.forEach { entry ->
            val typeRefText = entry.typeReference?.text
            if (typeRefText == shortName) return true // Direct match by short name (simplistic)
            // TODO: Add proper type resolution here for accuracy if needed
        }
        // Check super classes recursively (basic)
        // val superClass = currentClass.getSuperClass() // Helper needed
        // if (checkHierarchy(superClass)) return true
        return false
    }
    return checkHierarchy(ktClass)
}


// --- Inspection Logic ---

interface WSInterfaceParamInspectionLogic {
    val log: Logger

    fun validateInterfaceParams(
        project: Project,
        psiClass: PsiClass, // Use PsiClass as common ground for Java/Kotlin light class
        holder: ProblemsHolder
    ) {
        // 1. Basic checks (already done by visitor filters, but good practice)
        if (!psiClass.isInterface) return

        // 2. Check for @WSConsumer
        val wsConsumerAnnotation = psiClass.getAnnotation(WSCONSUMER_ANNOTATION_FQN) ?: return
        logIfEnabled(project, log, "Found @WSConsumer on interface ${psiClass.name}")

        // 3. Check if it implements WebserviceConsumer
        if (!isImplementingInterface(psiClass, WEBSERVICE_CONSUMER_FQN)) {
            logIfEnabled(project, log, "Interface ${psiClass.name} does not implement $WEBSERVICE_CONSUMER_FQN")
            return
        }
        logIfEnabled(project, log, "Interface ${psiClass.name} implements $WEBSERVICE_CONSUMER_FQN")

        // 4. Extract URL parameters
        // --- CORRECTED WAY TO GET STRING ATTRIBUTE VALUE ---
        val urlAttrValue = wsConsumerAnnotation.findAttributeValue("url")
        val urlValue = if (urlAttrValue is PsiLiteralExpression && urlAttrValue.value is String) {
            urlAttrValue.value as String
        } else {
            null // URL is not a string literal or not found
        }
        // --- END CORRECTION ---

        val urlParams = extractUrlParameters(urlValue)
        if (urlParams.isEmpty()) {
            logIfEnabled(project, log, "No @parameters found in URL for ${psiClass.name}")
            return // Nothing to validate
        }
        logIfEnabled(project, log, "URL Params for ${psiClass.name}: $urlParams")

        // 5. Find all relevant setter methods and their effective param names
        val methodParamMap = mutableMapOf<String, MutableList<PsiMethod>>() // Map: effectiveParamName -> List<Method>
        val methodsWithExplicitWsParamName = mutableMapOf<PsiMethod, String>() // Map: Method -> Explicit WSParam Name

        // Consider using psiClass.allMethods to include inherited methods if necessary
        // Using psiClass.methods only gets methods directly declared in this interface
        for (method in psiClass.methods) {
            val effectiveName = getEffectiveParamName(method) ?: continue // Skip non-setters or those without a name

            methodParamMap.computeIfAbsent(effectiveName) { mutableListOf() }.add(method)

            // Store methods with explicit @WSParam names for Rule 3
            val wsParamAnnotation = method.getAnnotation(WSPARAM_ANNOTATION_FQN)
            if (wsParamAnnotation != null) {
                // --- CORRECTED WAY TO GET STRING ATTRIBUTE VALUE ---
                val nameAttrValue = wsParamAnnotation.findAttributeValue("name")
                val explicitName = if (nameAttrValue is PsiLiteralExpression && nameAttrValue.value is String) {
                    nameAttrValue.value as String
                } else {
                    null
                }
                // --- END CORRECTION ---
                if (!explicitName.isNullOrBlank()) {
                    methodsWithExplicitWsParamName[method] = explicitName
                }
            }
        }
        logIfEnabled(project, log, "Effective Method Params for ${psiClass.name}: ${methodParamMap.keys}")

        // 6. Perform Validations

        // Rule 1: Check if all URL params have a corresponding setter
        val foundMethodParams = methodParamMap.keys
        val missingParams = urlParams - foundMethodParams
        if (missingParams.isNotEmpty()) {
            missingParams.forEach { missingParam ->
                logIfEnabled(project, log, "ERROR: Missing setter for URL param '@$missingParam'")
                // Highlight the URL attribute value if possible, otherwise the annotation/class name
                val highlightElement = wsConsumerAnnotation.findAttributeValue("url") ?: wsConsumerAnnotation.nameReferenceElement ?: psiClass.nameIdentifier ?: psiClass
                holder.registerProblem(
                    highlightElement,
                    MyBundle.message("inspection.wsinterfaceparam.error.missing.setter", missingParam),
                    ProblemHighlightType.ERROR
                )
            }
        }

        // Iterate through methods found
        methodParamMap.forEach { (effectiveParamName, methods) ->
            methods.forEach { method ->
                // Rule 2: Check for setter name mismatch (WARN)
                val derivedFromName = method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
                // Only warn if an explicit @WSParam name was NOT used to define the effective name
                if (effectiveParamName == derivedFromName && method !in methodsWithExplicitWsParamName) {
                    // This case is fine - name derived matches setter name
                } else if (effectiveParamName != derivedFromName && method !in methodsWithExplicitWsParamName) {
                    // This case shouldn't happen based on getEffectiveParamName logic, but check anyway
                    logIfEnabled(project, log, "WARN: Setter name '${method.name}' differs from derived param '$derivedFromName' unexpectedly.")
                } else if (effectiveParamName != derivedFromName && method in methodsWithExplicitWsParamName) {
                    // This is the intended warning: explicit @WSParam name used, differs from setter convention
                    logIfEnabled(project, log, "WARN: Setter name '${method.name}' differs from effective param '$effectiveParamName' defined by @WSParam.")
                    holder.registerProblem(
                        method.nameIdentifier ?: method,
                        MyBundle.message("inspection.wsinterfaceparam.warn.setter.name.mismatch", method.name, effectiveParamName),
                        ProblemHighlightType.WARNING // Severity WARN
                    )
                }


                // Rule 3: Check if explicit @WSParam name matches a URL param (ERROR)
                val explicitWsParamName = methodsWithExplicitWsParamName[method]
                if (explicitWsParamName != null && explicitWsParamName !in urlParams) {
                    logIfEnabled(project, log, "ERROR: Explicit @WSParam name '$explicitWsParamName' not in URL params $urlParams")
                    val wsParamNameValueElement = method.getAnnotation(WSPARAM_ANNOTATION_FQN)?.findAttributeValue("name")
                    holder.registerProblem(
                        wsParamNameValueElement ?: method.getAnnotation(WSPARAM_ANNOTATION_FQN) ?: method.nameIdentifier ?: method,
                        MyBundle.message("inspection.wsinterfaceparam.error.wsparam.name.mismatch", explicitWsParamName, urlParams.joinToString()),
                        ProblemHighlightType.ERROR // Severity ERROR
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

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitClass(psiClass: PsiClass) {
                super.visitClass(psiClass)
                // Filter for interfaces only before calling the main logic
                if (psiClass.isInterface) {
                    validateInterfaceParams(psiClass.project, psiClass, holder)
                }
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
            override fun visitClassOrObject(classOrObject: KtClassOrObject) { // Visit classes and objects
                super.visitClassOrObject(classOrObject)
                // Ensure it's an interface
                if (classOrObject is KtClass && classOrObject.isInterface()) {
                    // Try to get the corresponding PsiClass (light class) for consistent hierarchy checks
                    val psiClass = classOrObject.toLightClass()
                    if (psiClass != null) {
                        validateInterfaceParams(classOrObject.project, psiClass, holder)
                    } else {
                        logIfEnabled(classOrObject.project, log, "Could not get LightClass for Kotlin interface ${classOrObject.name}")
                        // Optionally add fallback logic using pure Kotlin PSI checks if light classes fail
                    }
                }
            }
        }
    }
}
