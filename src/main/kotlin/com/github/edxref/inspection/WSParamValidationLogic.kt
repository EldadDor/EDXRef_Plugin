package com.github.edxref.inspection

import com.github.edxref.MyBundle
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import java.util.regex.Pattern

// --- Settings Accessors (Copied here for shared use) ---
// It might be better to put these in a separate SettingsUtil file eventually
private fun getSettings(project: Project) = project.getWSConsumerSettings()
private fun getWsParamFqn(project: Project) = getSettings(project).wsParamAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSParam" }
private fun logIfEnabled(project: Project, logger: Logger, message: String) {
    try { if (getSettings(project).enableLog) logger.info(message) } catch (e: Exception) { /* ignore */ }
}

// --- Shared Helper Functions ---
/**
 * Extracts parameter names (like "userId") from placeholders like "@userId" in a URL.
 */
internal fun extractUrlParameters(url: String?): Set<String> {
    if (url.isNullOrBlank()) return emptySet()
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
internal fun getEffectiveParamName(method: PsiMethod): String? {
    if (!method.name.startsWith("set") || method.parameterList.parametersCount != 1) return null
    val project = method.project
    val wsParamAnnotation = method.getAnnotation(getWsParamFqn(project))
    if (wsParamAnnotation != null) {
        val nameAttrValue = wsParamAnnotation.findAttributeValue("name")
        val explicitName = if (nameAttrValue is PsiLiteralExpression && nameAttrValue.value is String) {
            nameAttrValue.value as String
        } else { null }
        if (!explicitName.isNullOrBlank()) return explicitName
    }
    if (method.name.length > 3) {
        return method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
    }
    return null
}

// --- Shared Validation Logic Interface ---
interface WSParamValidationLogic {
    // Requires implementing class to provide its logger
    val log: Logger

    /**
     * Performs validation rules comparing URL parameters against setter methods.
     * This is the core shared logic.
     */
    fun validateUrlParamsAgainstSetters(
        project: Project,
        urlParams: Set<String>,
        urlAttrValue: PsiAnnotationMemberValue?, // For highlighting missing setters
        methods: Array<PsiMethod>, // Pass the relevant methods (class or interface)
        wsConsumerAnnotation: PsiAnnotation, // For context and highlighting
        holder: ProblemsHolder
    ) {
        if (urlParams.isEmpty()) {
            logIfEnabled(project, log, "Skipping URL parameter validation as none were found.")
            return
        }

        // 1. Process methods to find setters and map effective names
        val methodParamMap = mutableMapOf<String, MutableList<PsiMethod>>()
        val methodsWithExplicitWsParamName = mutableMapOf<PsiMethod, String>()
        val wsParamFqn = getWsParamFqn(project)

        for (method in methods) {
            // Check if it's a setter and get its effective name
            val effectiveName = getEffectiveParamName(method) ?: continue // Skips non-setters

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
        logIfEnabled(project, log, "Effective Method Params found: ${methodParamMap.keys}")


        // 2. Rule: Check if all URL params have a corresponding setter
        val foundMethodParams = methodParamMap.keys
        val missingParams = urlParams - foundMethodParams
        if (missingParams.isNotEmpty()) {
            missingParams.forEach { missingParam ->
                logIfEnabled(project, log, "ERROR: Missing setter for URL param '@$missingParam'")
                val highlightElement = urlAttrValue ?: wsConsumerAnnotation.nameReferenceElement ?: wsConsumerAnnotation
                holder.registerProblem(
                    highlightElement,
                    MyBundle.message("inspection.wsparam.error.missing.setter", missingParam), // Use generic key
                    ProblemHighlightType.ERROR
                )
            }
        }

        // 3. Iterate through mapped methods for other rules
        methodParamMap.forEach { (effectiveParamName, mappedMethods) ->
            mappedMethods.forEach { method ->
                // Rule: Check for setter name mismatch (WARN)
                val derivedFromName = method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
                if (effectiveParamName != derivedFromName && method in methodsWithExplicitWsParamName) {
                    logIfEnabled(project, log, "WARN: Setter name '${method.name}' differs from effective param '$effectiveParamName' defined by @WSParam.")
                    holder.registerProblem(
                        method.nameIdentifier ?: method,
                        MyBundle.message("inspection.wsparam.warn.setter.name.mismatch", method.name, effectiveParamName), // Use generic key
                        ProblemHighlightType.WARNING
                    )
                }

                // Rule: Check if explicit @WSParam name matches a URL param (ERROR)
                val explicitWsParamName = methodsWithExplicitWsParamName[method]
                if (explicitWsParamName != null && explicitWsParamName !in urlParams) {
                    logIfEnabled(project, log, "ERROR: Explicit @WSParam name '$explicitWsParamName' not in URL params $urlParams")
                    val wsParamNameValueElement = method.getAnnotation(wsParamFqn)?.findAttributeValue("name")
                    holder.registerProblem(
                        wsParamNameValueElement ?: method.getAnnotation(wsParamFqn) ?: method.nameIdentifier ?: method,
                        MyBundle.message("inspection.wsparam.error.wsparam.name.mismatch", explicitWsParamName, urlParams.joinToString()), // Use generic key
                        ProblemHighlightType.ERROR
                    )
                }
            }
        }
    }
}
