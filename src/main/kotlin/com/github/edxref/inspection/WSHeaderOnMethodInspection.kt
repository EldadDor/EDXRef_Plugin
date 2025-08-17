package com.github.edxref.inspection

import com.github.edxref.MyBundle
import com.intellij.codeInspection.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

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
		containingClass.getAnnotation(InspectionUtil.getWsConsumerAnnotationFqn(project)) ?: return
		if (!InspectionUtil.isWebserviceConsumer(containingClass)) return

		InspectionUtil.logIfEnabled(project, log, "Running WSHeaderOnMethod validation on ${containingClass.name}.${method.name}")

		// 2. Get headers on the current method
		val methodHeaders = getMethodHeaders(method, project)
		if (methodHeaders.isEmpty()) return // Nothing to validate on this method

		// 3. Get type headers for redundancy check
		val typeHeaders = getTypeHeadersMap(containingClass, project)

		// 4. Validate each header on the method
		val isSetter = method.name.startsWith("set") && method.parameterList.parametersCount == 1

		methodHeaders.forEach { (methodHeaderName, methodHeaderAnnotation) ->
			// Rule 1: Validate defaultValue on setters
			if (isSetter) {
				validateSetterHeaderDefault(method, methodHeaderName, methodHeaderAnnotation, holder, log)
			}

			// Rule 2: Check for redundancy against type headers
			if (typeHeaders.containsKey(methodHeaderName)) {
				InspectionUtil.logIfEnabled(project, log, "WARN: Redundant header '$methodHeaderName' on method '${method.name}' also defined on type '${containingClass.name}'")
				holder.registerProblem(
					method.nameIdentifier ?: method,
					MyBundle.message("inspection.wsheaderonmethod.warn.redundant.method.header", methodHeaderName),
					ProblemHighlightType.WARNING
				)
			}
		}
	}

	// K2-compatible validation for Kotlin methods
	fun validateKotlinMethodHeader(
		project: Project,
		function: KtNamedFunction,
		holder: ProblemsHolder
	) {
		// 1. Prerequisites for the containing class
		val containingClass = function.containingClassOrObject as? KtClassOrObject ?: return
		if (!InspectionUtil.hasWSConsumerAnnotation(project, containingClass)) return
		if (!InspectionUtil.isKotlinWebserviceConsumer(containingClass)) return

		InspectionUtil.logIfEnabled(project, log, "Running WSHeaderOnMethod validation on Kotlin ${containingClass.name}.${function.name}")

		// 2. Get headers on the current function
		val functionHeaders = getKotlinFunctionHeaders(function, project)
		if (functionHeaders.isEmpty()) return // Nothing to validate on this function

		// 3. Get type headers for redundancy check (convert to light class for this)
		val lightClass = containingClass.toLightClass() ?: return
		val typeHeaders = getTypeHeadersMap(lightClass, project)

		// 4. Validate each header on the function
		val isSetter = function.name?.startsWith("set") == true && function.valueParameters.size == 1

		functionHeaders.forEach { (methodHeaderName, methodHeaderAnnotation) ->
			// Rule 1: Validate defaultValue on setters
			if (isSetter) {
				validateKotlinSetterHeaderDefault(function, methodHeaderName, methodHeaderAnnotation, holder, log)
			}

			// Rule 2: Check for redundancy against type headers
			if (typeHeaders.containsKey(methodHeaderName)) {
				InspectionUtil.logIfEnabled(project, log, "WARN: Redundant header '$methodHeaderName' on Kotlin function '${function.name}' also defined on type '${containingClass.name}'")
				holder.registerProblem(
					function.nameIdentifier ?: function,
					MyBundle.message("inspection.wsheaderonmethod.warn.redundant.method.header", methodHeaderName),
					ProblemHighlightType.WARNING
				)
			}
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
		holder: ProblemsHolder,
		logger: Logger
	) {
		val defaultValueAttr = headerAnnotation.findAttributeValue("defaultValue")
		if (defaultValueAttr is PsiLiteralExpression) {
			val value = defaultValueAttr.value as? String
			if (value != null && value.isEmpty()) {
				InspectionUtil.logIfEnabled(method.project, logger, "ERROR: Setter header '$headerName' on method '${method.name}' has an empty defaultValue.")
				holder.registerProblem(
					method.nameIdentifier ?: method,
					MyBundle.message("inspection.wsheaderonmethod.error.invalid.setter.defaultvalue", headerName, method.name),
					ProblemHighlightType.ERROR
				)
			}
		}
	}

	/**
	 * K2-compatible validation for Kotlin setter headers
	 */
	private fun validateKotlinSetterHeaderDefault(
		function: KtNamedFunction,
		headerName: String,
		headerAnnotation: KtAnnotationEntry,
		holder: ProblemsHolder,
		logger: Logger
	) {
		val defaultValueArg = headerAnnotation.valueArguments.find {
			it.getArgumentName()?.asName?.asString() == "defaultValue"
		}

		val expr = defaultValueArg?.getArgumentExpression()
		if (expr is KtStringTemplateExpression) {
			val text = expr.text
			if (text.length == 2 || (text.startsWith("\"") && text.endsWith("\"") && text.substring(1, text.length - 1).isEmpty())) {
				InspectionUtil.logIfEnabled(function.project, logger, "ERROR: Kotlin setter header '$headerName' on function '${function.name}' has an empty defaultValue.")
				holder.registerProblem(
					function.nameIdentifier ?: function,
					MyBundle.message("inspection.wsheaderonmethod.error.invalid.setter.defaultvalue", headerName, function.name ?: "unknown"),
					ProblemHighlightType.ERROR
				)
			}
		}
	}

	// Helper to get method headers
	private fun getMethodHeaders(method: PsiMethod, project: Project): Map<String, PsiAnnotation> {
		val headersMap = mutableMapOf<String, PsiAnnotation>()
		val wsHeaderFqn = InspectionUtil.getWsHeaderFqn(project)
		method.annotations.filter { it.hasQualifiedName(wsHeaderFqn) }.forEach { headerAnnotation ->
			InspectionUtil.getAnnotationStringAttribute(headerAnnotation, "name")?.let { name ->
				if (name.isNotBlank()) headersMap[name] = headerAnnotation
			}
		}
		return headersMap
	}

	// Helper to get Kotlin function headers
	private fun getKotlinFunctionHeaders(function: KtNamedFunction, project: Project): Map<String, KtAnnotationEntry> {
		val headersMap = mutableMapOf<String, KtAnnotationEntry>()
		val wsHeaderShortName = InspectionUtil.getShortName(InspectionUtil.getWsHeaderFqn(project))

		function.annotationEntries.filter { it.shortName?.asString() == wsHeaderShortName }.forEach { headerAnnotation ->
			InspectionUtil.getKotlinAnnotationStringAttribute(headerAnnotation, "name")?.let { name ->
				if (name.isNotBlank()) headersMap[name] = headerAnnotation
			}
		}
		return headersMap
	}

	// Helper to get type headers (for redundancy check)
	private fun getTypeHeadersMap(psiClass: PsiClass, project: Project): Map<String, PsiAnnotation> {
		val headersMap = mutableMapOf<String, PsiAnnotation>()
		val wsHeaderFqn = InspectionUtil.getWsHeaderFqn(project)
		val wsHeadersFqn = InspectionUtil.getWsHeadersFqn(project)

		val headersAnnotation = psiClass.getAnnotation(wsHeadersFqn)
		if (headersAnnotation != null) {
			val valueAttr = headersAnnotation.findAttributeValue("value")
			if (valueAttr is PsiArrayInitializerMemberValue) {
				valueAttr.initializers.forEach { initializer ->
					if (initializer is PsiAnnotation && initializer.hasQualifiedName(wsHeaderFqn)) {
						InspectionUtil.getAnnotationStringAttribute(initializer, "name")?.let { name ->
							if (name.isNotBlank()) headersMap[name] = initializer
						}
					}
				}
			}
		}

		val singleHeaderAnnotation = psiClass.getAnnotation(wsHeaderFqn)
		if (singleHeaderAnnotation != null) {
			InspectionUtil.getAnnotationStringAttribute(singleHeaderAnnotation, "name")?.let { name ->
				if (name.isNotBlank() && !headersMap.containsKey(name)) headersMap[name] = singleHeaderAnnotation
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
			override fun visitMethod(method: PsiMethod) {
				super.visitMethod(method)
				validateMethodHeader(method.project, method, holder)
			}
		}
	}
}

// --- Kotlin Inspection ---
class WSHeaderOnMethodKotlinInspection : AbstractBaseUastLocalInspectionTool(), WSHeaderOnMethodInspectionLogic {
	override val log = logger<WSHeaderOnMethodKotlinInspection>()
	override fun getDisplayName(): String = MyBundle.message("inspection.wsheaderonmethod.displayname")

	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		return object : KtVisitorVoid() {
			override fun visitNamedFunction(function: KtNamedFunction) {
				super.visitNamedFunction(function)
				validateKotlinMethodHeader(function.project, function, holder)
			}
		}
	}
}