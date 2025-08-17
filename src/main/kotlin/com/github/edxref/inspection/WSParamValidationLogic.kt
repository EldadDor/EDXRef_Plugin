package com.github.edxref.inspection

import com.github.edxref.MyBundle
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import java.util.regex.Pattern
import org.jetbrains.kotlin.psi.KtNamedFunction

// --- Settings Accessors (Copied here for shared use) ---
// It might be better to put these in a separate SettingsUtil file eventually
private fun getSettings(project: Project) = project.getWSConsumerSettings()

private fun getWsParamFqn(project: Project) =
  getSettings(project).wsParamAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSParam" }

private fun logIfEnabled(project: Project, logger: Logger, message: String) {
  try {
    if (getSettings(project).enableLog) logger.info(message)
  } catch (e: Exception) {
    /* ignore */
  }
}

// --- Shared Helper Functions ---
/** Extracts parameter names (like "userId") from placeholders like "@userId" in a URL. */
/*internal fun extractUrlParameters(url: String?): Set<String> {
	if (url.isNullOrBlank()) return emptySet()
	val params = mutableSetOf<String>()
	val pattern = Pattern.compile("@(\\w+)")
	val matcher = pattern.matcher(url)
	while (matcher.find()) {
		params.add(matcher.group(1))
	}
	return params
}*/

/** Gets the effective parameter name from a setter method using configurable FQN. */
/*internal fun getEffectiveParamName(method: PsiMethod): String? {
	if (!method.name.startsWith("set") || method.parameterList.parametersCount != 1) return null
	val project = method.project
	val wsParamAnnotation = method.getAnnotation(getWsParamFqn(project))
	if (wsParamAnnotation != null) {
		val nameAttrValue = wsParamAnnotation.findAttributeValue("name")
		val explicitName = if (nameAttrValue is PsiLiteralExpression && nameAttrValue.value is String) {
			nameAttrValue.value as String
		} else {
			null
		}
		if (!explicitName.isNullOrBlank()) return explicitName
	}
	if (method.name.length > 3) {
		return method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
	}
	return null
}*/

/**
 * Gets the effective parameter name from a Kotlin setter function using configurable FQN.
 * K2-compatible version that works with KtNamedFunction directly.
 */
// internal fun getEffectiveParamName(function: KtNamedFunction): String? {
//	if (!function.name?.startsWith("set")!! || function.valueParameters.size != 1) return null
//
//	val project = function.project
//	val wsParamFqn = getWsParamFqn(project)
//	val wsParamShortName = wsParamFqn.substringAfterLast('.')
//
//	// Check for @WSParam annotation on the Kotlin function
//	val wsParamAnnotation = function.annotationEntries.find {
//		it.shortName?.asString() == wsParamShortName
//	}
//
//	if (wsParamAnnotation != null) {
//		val nameArg = wsParamAnnotation.valueArguments.find {
//			it.getArgumentName()?.asName?.asString() == "name"
//		}
//		val expr = nameArg?.getArgumentExpression()
//		if (expr is KtStringTemplateExpression) {
//			val text = expr.text
//			if (text.length > 2 && text.startsWith("\"") && text.endsWith("\"")) {
//				val explicitName = text.substring(1, text.length - 1)
//				if (explicitName.isNotBlank()) return explicitName
//			}
//		}
//	}
//
//	// Derive from function name
//	val functionName = function.name ?: return null
//	if (functionName.length > 3) {
//		return functionName.substring(3).replaceFirstChar { it.lowercaseChar() }
//	}
//	return null
// }

// --- Shared Helper Functions ---
/** Extracts parameter names (like "userId") from placeholders like "@userId" in a URL. */
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

/** Gets the effective parameter name from a setter method using configurable FQN. */
internal fun getEffectiveParamName(method: PsiMethod): String? {
  if (!method.name.startsWith("set") || method.parameterList.parametersCount != 1) return null
  val project = method.project
  val wsParamAnnotation = method.getAnnotation(InspectionUtil.getWsParamFqn(project))
  if (wsParamAnnotation != null) {
    val explicitName = InspectionUtil.getAnnotationStringAttribute(wsParamAnnotation, "name")
    if (!explicitName.isNullOrBlank()) return explicitName
  }
  if (method.name.length > 3) {
    return method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
  }
  return null
}

/**
 * Gets the effective parameter name from a Kotlin setter function using configurable FQN.
 * K2-compatible version that works with KtNamedFunction directly.
 */
internal fun getEffectiveParamName(function: KtNamedFunction): String? {
  if (!function.name?.startsWith("set")!! || function.valueParameters.size != 1) return null

  val project = function.project
  val wsParamShortName = InspectionUtil.getShortName(InspectionUtil.getWsParamFqn(project))

  // Check for @WSParam annotation on the Kotlin function
  val wsParamAnnotation = InspectionUtil.findKotlinFunctionAnnotation(function, wsParamShortName)

  if (wsParamAnnotation != null) {
    val explicitName = InspectionUtil.getKotlinAnnotationStringAttribute(wsParamAnnotation, "name")
    if (!explicitName.isNullOrBlank()) return explicitName
  }

  // Derive from function name
  val functionName = function.name ?: return null
  if (functionName.length > 3) {
    return functionName.substring(3).replaceFirstChar { it.lowercaseChar() }
  }
  return null
}

// --- Shared Validation Logic Interface ---
interface WSParamValidationLogic {
  // Requires implementing class to provide its logger
  val log: Logger

  /**
   * Generic validation that works with both PsiMethod arrays and KtNamedFunction lists.
   * K2-compatible version that handles both Java and Kotlin elements.
   */
  fun validateUrlParamsAgainstSetters(
    project: Project,
    urlParams: Set<String>,
    urlAttrValue: PsiAnnotationMemberValue?, // For highlighting missing setters
    methods: Array<PsiMethod>, // Java methods
    kotlinFunctions: List<KtNamedFunction> = emptyList(), // Kotlin functions
    wsConsumerAnnotation: PsiAnnotation, // For context and highlighting
    holder: ProblemsHolder,
    log: Logger,
  ) {
    if (urlParams.isEmpty()) {
      InspectionUtil.logIfEnabled(
        project,
        log,
        "Skipping URL parameter validation as none were found.",
      )
      return
    }

    // 1. Process both Java methods and Kotlin functions
    val methodParamMap =
      mutableMapOf<String, MutableList<Any>>() // Any = PsiMethod or KtNamedFunction
    val methodsWithExplicitWsParamName = mutableMapOf<Any, String>()
    val wsParamFqn = InspectionUtil.getWsParamFqn(project)
    val wsParamShortName = InspectionUtil.getShortName(wsParamFqn)

    // Process Java methods
    for (method in methods) {
      val effectiveName = getEffectiveParamName(method) ?: continue
      methodParamMap.computeIfAbsent(effectiveName) { mutableListOf() }.add(method)

      val wsParamAnnotation = method.getAnnotation(wsParamFqn)
      if (wsParamAnnotation != null) {
        val explicitName = InspectionUtil.getAnnotationStringAttribute(wsParamAnnotation, "name")
        if (!explicitName.isNullOrBlank()) {
          methodsWithExplicitWsParamName[method] = explicitName
        }
      }
    }

    // Process Kotlin functions
    for (function in kotlinFunctions) {
      val effectiveName = getEffectiveParamName(function) ?: continue
      methodParamMap.computeIfAbsent(effectiveName) { mutableListOf() }.add(function)

      val wsParamAnnotation =
        InspectionUtil.findKotlinFunctionAnnotation(function, wsParamShortName)
      if (wsParamAnnotation != null) {
        val explicitName =
          InspectionUtil.getKotlinAnnotationStringAttribute(wsParamAnnotation, "name")
        if (!explicitName.isNullOrBlank()) {
          methodsWithExplicitWsParamName[function] = explicitName
        }
      }
    }

    InspectionUtil.logIfEnabled(
      project,
      log,
      "Effective Method Params found: ${methodParamMap.keys}",
    )

    // 2. Rule: Check if all URL params have a corresponding setter
    val foundMethodParams = methodParamMap.keys
    val missingParams = urlParams - foundMethodParams
    if (missingParams.isNotEmpty()) {
      missingParams.forEach { missingParam ->
        InspectionUtil.logIfEnabled(
          project,
          log,
          "ERROR: Missing setter for URL param '@$missingParam'",
        )
        val highlightElement =
          urlAttrValue ?: wsConsumerAnnotation.nameReferenceElement ?: wsConsumerAnnotation
        holder.registerProblem(
          highlightElement,
          MyBundle.message("inspection.wsparam.error.missing.setter", missingParam),
          ProblemHighlightType.ERROR,
        )
      }
    }

    // 3. Iterate through mapped methods/functions for other rules
    methodParamMap.forEach { (effectiveParamName, mappedElements) ->
      mappedElements.forEach { element ->
        when (element) {
          is PsiMethod -> {
            validateMethodRules(
              element,
              effectiveParamName,
              methodsWithExplicitWsParamName,
              urlParams,
              wsParamFqn,
              holder,
              project,
              log,
            )
          }

          is KtNamedFunction -> {
            validateKotlinFunctionRules(
              element,
              effectiveParamName,
              methodsWithExplicitWsParamName,
              urlParams,
              wsParamShortName,
              holder,
              project,
              log,
            )
          }
        }
      }
    }
  }

  /** @deprecated Use the generic version that supports both Java and Kotlin */
  @Deprecated("Use generic version with kotlinFunctions parameter")
  fun validateUrlParamsAgainstSetters(
    project: Project,
    urlParams: Set<String>,
    urlAttrValue: PsiAnnotationMemberValue?,
    methods: Array<PsiMethod>,
    wsConsumerAnnotation: PsiAnnotation,
    holder: ProblemsHolder,
  ) {
    // Delegate to generic version with empty Kotlin functions list
    validateUrlParamsAgainstSetters(
      project,
      urlParams,
      urlAttrValue,
      methods,
      emptyList(),
      wsConsumerAnnotation,
      holder,
      logger<WSParamValidationLogic>(),
    )
  }
}

private fun validateMethodRules(
  method: PsiMethod,
  effectiveParamName: String,
  methodsWithExplicitWsParamName: Map<Any, String>,
  urlParams: Set<String>,
  wsParamFqn: String,
  holder: ProblemsHolder,
  project: Project,
  log: Logger,
) {
  // Rule: Check for setter name mismatch (WARN)
  val derivedFromName = method.name.substring(3).replaceFirstChar { it.lowercaseChar() }
  if (effectiveParamName != derivedFromName && method in methodsWithExplicitWsParamName) {
    InspectionUtil.logIfEnabled(
      project,
      log,
      "WARN: Setter name '${method.name}' differs from effective param '$effectiveParamName' defined by @WSParam.",
    )
    holder.registerProblem(
      method.nameIdentifier ?: method,
      MyBundle.message(
        "inspection.wsparam.warn.setter.name.mismatch",
        method.name,
        effectiveParamName,
      ),
      ProblemHighlightType.WARNING,
    )
  }

  // Rule: Check if explicit @WSParam name matches a URL param (ERROR)
  val explicitWsParamName = methodsWithExplicitWsParamName[method]
  if (explicitWsParamName != null && explicitWsParamName !in urlParams) {
    InspectionUtil.logIfEnabled(
      project,
      log,
      "ERROR: Explicit @WSParam name '$explicitWsParamName' not in URL params $urlParams",
    )
    val wsParamNameValueElement = method.getAnnotation(wsParamFqn)?.findAttributeValue("name")
    holder.registerProblem(
      wsParamNameValueElement
        ?: method.getAnnotation(wsParamFqn)
        ?: method.nameIdentifier
        ?: method,
      MyBundle.message(
        "inspection.wsparam.error.wsparam.name.mismatch",
        explicitWsParamName,
        urlParams.joinToString(),
      ),
      ProblemHighlightType.ERROR,
    )
  }
}

private fun validateKotlinFunctionRules(
  function: KtNamedFunction,
  effectiveParamName: String,
  methodsWithExplicitWsParamName: Map<Any, String>,
  urlParams: Set<String>,
  wsParamShortName: String,
  holder: ProblemsHolder,
  project: Project,
  log: Logger,
) {
  // Rule: Check for setter name mismatch (WARN)
  val functionName = function.name ?: return
  val derivedFromName = functionName.substring(3).replaceFirstChar { it.lowercaseChar() }
  if (effectiveParamName != derivedFromName && function in methodsWithExplicitWsParamName) {
    InspectionUtil.logIfEnabled(
      project,
      log,
      "WARN: Kotlin setter name '$functionName' differs from effective param '$effectiveParamName' defined by @WSParam.",
    )
    holder.registerProblem(
      function.nameIdentifier ?: function,
      MyBundle.message(
        "inspection.wsparam.warn.setter.name.mismatch",
        functionName,
        effectiveParamName,
      ),
      ProblemHighlightType.WARNING,
    )
  }

  // Rule: Check if explicit @WSParam name matches a URL param (ERROR)
  val explicitWsParamName = methodsWithExplicitWsParamName[function]
  if (explicitWsParamName != null && explicitWsParamName !in urlParams) {
    InspectionUtil.logIfEnabled(
      project,
      log,
      "ERROR: Explicit Kotlin @WSParam name '$explicitWsParamName' not in URL params $urlParams",
    )
    val wsParamAnnotation = InspectionUtil.findKotlinFunctionAnnotation(function, wsParamShortName)
    val nameArg =
      wsParamAnnotation?.valueArguments?.find { it.getArgumentName()?.asName?.asString() == "name" }
    val highlightElement =
      nameArg?.getArgumentExpression() ?: wsParamAnnotation ?: function.nameIdentifier ?: function
    holder.registerProblem(
      highlightElement,
      MyBundle.message(
        "inspection.wsparam.error.wsparam.name.mismatch",
        explicitWsParamName,
        urlParams.joinToString(),
      ),
      ProblemHighlightType.ERROR,
    )
  }
}
