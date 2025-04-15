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
// It's good practice to define defaults in case settings are empty or unavailable
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
    // Consider adding error handling if service can't be retrieved
    return project.getWSConsumerSettings()
}

private fun getWsConsumerAnnotationFqn(project: Project): String {
    return getSettings(project).wsConsumerAnnotationFqn.ifBlank { DEFAULT_WSCONSUMER_ANNOTATION_FQN }
}

private fun getWebserviceConsumerFqn(project: Project): String { // Renamed for clarity
    return getSettings(project).webserviceConsumerFqn.ifBlank { DEFAULT_WEBSERVICE_CONSUMER_FQN }
}

private fun getWsParamFqn(project: Project): String {
    return getSettings(project).wsParamAnnotationFqn.ifBlank { DEFAULT_WSPARAM_ANNOTATION_FQN }
}

private fun getPropertyFqn(project: Project): String { // Renamed for clarity
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
    val pattern = Pattern.compile("@(\\w+)") // Regex to find @ followed by word characters
    val matcher = pattern.matcher(url)
    while (matcher.find()) {
        params.add(matcher.group(1)) // Add the group captured (the name without @)
    }
    return params
}

/**
 * Gets the effective parameter name from a setter method using configurable FQN.
 * Uses @WSParam(name=...) if present and non-empty, otherwise derives from the setter name.
 * Returns null if it's not a valid setter or cannot determine a name.
 */
private fun getEffectiveParamName(method: PsiMethod): String? {
    if (!method.name.startsWith("set") || method.parameterList.parametersCount != 1) {
        return null // Not a standard setter
    }
    val project = method.project
    val wsParamAnnotation = method.getAnnotation(getWsParamFqn(project)) // Use setting
    if (wsParamAnnotation != null) {
        val nameAttrValue = wsParamAnnotation.findAttributeValue("name")
        val explicitName = if (nameAttrValue is PsiLiteralExpression && nameAttrValue.value is String) {
            nameAttrValue.value as String
        } else {
            null
        }
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

// Kotlin version - Needs update to use settings FQN if pure Kotlin PSI checks are added later
private fun getEffectiveParamName(function: KtNamedFunction): String? {
    val functionName = function.nameIdentifier?.text ?: return null
    if (!functionName.startsWith("set") || function.valueParameters.size != 1) {
        return null // Not a standard setter
    }
    val project = function.project
    // Note: This Kotlin check still uses shortName. For accuracy with settings,
    // rely on the PsiMethod version via toLightClass or implement full Kotlin type resolution.
    val wsParamAnnotation = function.annotationEntries.find {
        it.shortName?.asString() == getWsParamFqn(project).substringAfterLast('.') // Basic short name check
    }

    if (wsParamAnnotation != null) {
        val nameArgument = wsParamAnnotation.valueArguments.find { it.getArgumentName()?.asName?.asString() == "name" }
        val explicitNameExpr = nameArgument?.getArgumentExpression()
        if (explicitNameExpr is KtStringTemplateExpression) {
            val explicitName = explicitNameExpr.entries
                .filterIsInstance<KtLiteralStringTemplateEntry>()
                .joinToString("") { it.text }
            if (explicitName.isNotBlank()) {
                return explicitName
            }
        }
    }

    if (functionName.length > 3) {
        return functionName.substring(3).replaceFirstChar { it.lowercaseChar() }
    }
    return null
}


/**
 * Checks if a class/interface inherits from a specific fully qualified name.
 */
private fun isImplementingInterface(psiClass: PsiClass, interfaceFqn: String): Boolean {
    // Handle cases where FQN might be empty from settings
    if (interfaceFqn.isBlank()) return false
    return InheritanceUtil.isInheritor(psiClass, interfaceFqn)
}

// Kotlin version
private fun isImplementingInterface(ktClass: KtClass, interfaceFqn: String): Boolean {
    if (interfaceFqn.isBlank()) return false
    val lightClass = ktClass.toLightClass()
    if (lightClass != null) {
        return InheritanceUtil.isInheritor(lightClass, interfaceFqn)
    }
    // Basic fallback (less reliable)
    val shortName = interfaceFqn.substringAfterLast('.')
    // ... (rest of basic fallback if needed) ...
    return false // Prefer light class check
}


// --- Inspection Logic ---

interface WSInterfaceParamInspectionLogic {
    val log: Logger

    fun validateInterfaceParams(
        project: Project,
        psiClass: PsiClass, // Use PsiClass as common ground for Java/Kotlin light class
        holder: ProblemsHolder
    ) {
        // 1. Basic checks
        if (!psiClass.isInterface) return

        // 2. Check for @WSConsumer using settings FQN
        val wsConsumerAnnotationFqn = getWsConsumerAnnotationFqn(project)
        val wsConsumerAnnotation = psiClass.getAnnotation(wsConsumerAnnotationFqn) ?: return
        logIfEnabled(project, log, "Found @${wsConsumerAnnotationFqn.substringAfterLast('.')} on interface ${psiClass.name}")

        // 3. Check if it implements WebserviceConsumer using settings FQN
        val webserviceConsumerFqn = getWebserviceConsumerFqn(project)
        if (!isImplementingInterface(psiClass, webserviceConsumerFqn)) {
            logIfEnabled(project, log, "Interface ${psiClass.name} does not implement $webserviceConsumerFqn")
            return
        }
        logIfEnabled(project, log, "Interface ${psiClass.name} implements $webserviceConsumerFqn")

        // 4. Extract URL parameters
        val urlAttrValue = wsConsumerAnnotation.findAttributeValue("url")
        val urlValue = if (urlAttrValue is PsiLiteralExpression && urlAttrValue.value is String) {
            urlAttrValue.value as String
        } else {
            null
        }

        val urlParams = extractUrlParameters(urlValue)
        if (urlParams.isEmpty()) {
            logIfEnabled(project, log, "No @parameters found in URL for ${psiClass.name}")
            // Even if no URL params, we still need to check for missing @Property on setters
            // return // DO NOT return early
        }
        logIfEnabled(project, log, "URL Params for ${psiClass.name}: $urlParams")

        // 5. Find all relevant setter methods, check @Property, and map effective param names
        val methodParamMap = mutableMapOf<String, MutableList<PsiMethod>>()
        val methodsWithExplicitWsParamName = mutableMapOf<PsiMethod, String>()
        val propertyAnnotationFqn = getPropertyFqn(project) // Get Property FQN from settings
        val propertyAnnotationShortName = propertyAnnotationFqn.substringAfterLast('.')

        // Use psiClass.methods to check only methods declared directly in this interface
        for (method in psiClass.methods) {
            // Check if it looks like a setter BEFORE checking @Property
            if (!method.name.startsWith("set") || method.parameterList.parametersCount != 1) {
                continue // Skip non-setter methods
            }

            // *** NEW: Check for @Property annotation ***
            if (method.getAnnotation(propertyAnnotationFqn) == null) {
                logIfEnabled(project, log, "ERROR: Setter method '${method.name}' is missing the @$propertyAnnotationShortName annotation.")
                holder.registerProblem(
                    method.nameIdentifier ?: method, // Highlight method name
                    MyBundle.message("inspection.wsinterfaceparam.error.missing.property.annotation", method.name, propertyAnnotationShortName),
                    ProblemHighlightType.ERROR
                )
                // Continue processing other rules for this method even if @Property is missing
            }
            // *** END NEW CHECK ***

            // Now get the effective name for URL param matching
            val effectiveName = getEffectiveParamName(method)
            if (effectiveName != null) { // Only map if it has an effective name (relevant to URL params or explicit @WSParam)
                methodParamMap.computeIfAbsent(effectiveName) { mutableListOf() }.add(method)

                // Store methods with explicit @WSParam names for Rule 3
                val wsParamAnnotation = method.getAnnotation(getWsParamFqn(project)) // Use setting
                if (wsParamAnnotation != null) {
                    val nameAttrValue = wsParamAnnotation.findAttributeValue("name")
                    val explicitName = if (nameAttrValue is PsiLiteralExpression && nameAttrValue.value is String) {
                        nameAttrValue.value as String
                    } else {
                        null
                    }
                    if (!explicitName.isNullOrBlank()) {
                        methodsWithExplicitWsParamName[method] = explicitName
                    }
                }
            }
        }
        logIfEnabled(project, log, "Effective Method Params for ${psiClass.name}: ${methodParamMap.keys}")

        // 6. Perform URL Parameter Validations (only if there were URL params)
        if (urlParams.isNotEmpty()) {
            // Rule 1: Check if all URL params have a corresponding setter
            val foundMethodParams = methodParamMap.keys
            val missingParams = urlParams - foundMethodParams
            if (missingParams.isNotEmpty()) {
                missingParams.forEach { missingParam ->
                    logIfEnabled(project, log, "ERROR: Missing setter for URL param '@$missingParam'")
                    val highlightElement = wsConsumerAnnotation.findAttributeValue("url") ?: wsConsumerAnnotation.nameReferenceElement ?: psiClass.nameIdentifier ?: psiClass
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
                        // Warn only if explicit @WSParam name differs from setter convention
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
                        val wsParamNameValueElement = method.getAnnotation(getWsParamFqn(project))?.findAttributeValue("name")
                        holder.registerProblem(
                            wsParamNameValueElement ?: method.getAnnotation(getWsParamFqn(project)) ?: method.nameIdentifier ?: method,
                            MyBundle.message("inspection.wsinterfaceparam.error.wsparam.name.mismatch", explicitWsParamName, urlParams.joinToString()),
                            ProblemHighlightType.ERROR // Severity ERROR
                        )
                    }
                }
            }
        } // End of URL parameter validation block
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
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                super.visitClassOrObject(classOrObject)
                if (classOrObject is KtClass && classOrObject.isInterface()) {
                    val psiClass = classOrObject.toLightClass()
                    if (psiClass != null) {
                        validateInterfaceParams(classOrObject.project, psiClass, holder)
                    } else {
                        logIfEnabled(classOrObject.project, log, "Could not get LightClass for Kotlin interface ${classOrObject.name}")
                    }
                }
            }
        }
    }
}
