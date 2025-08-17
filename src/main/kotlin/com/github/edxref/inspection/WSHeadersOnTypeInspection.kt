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

// Helper for Kotlin annotations
private fun getKotlinAnnotationStringAttribute(annotation: KtAnnotationEntry, attributeName: String): String? {
	val arg = annotation.valueArguments.find {
		it.getArgumentName()?.asName?.asString() == attributeName
	} ?: return null
	val expr = arg.getArgumentExpression()
	val text = expr?.text ?: return null
	return if (expr is KtStringTemplateExpression && text.startsWith("\"") && text.endsWith("\"")) {
		text.substring(1, text.length - 1)
	} else {
		text
	}
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
	}

	// K2-compatible validation for Kotlin classes
	fun validateKotlinTypeHeaders(
		project: Project,
		classOrObject: KtClassOrObject,
		holder: ProblemsHolder
	) {
		// 1. Check for @WSConsumer annotation using direct Kotlin PSI
		val wsConsumerAnnotationShortName = getWsConsumerAnnotationFqn(project).substringAfterLast('.')
		val hasWSConsumer = classOrObject.annotationEntries.any {
			it.shortName?.asString() == wsConsumerAnnotationShortName
		}
		if (!hasWSConsumer) return

		// 2. Check if it's a webservice consumer (fallback to light class only if needed)
		val psiClass = classOrObject.toLightClass()
		if (psiClass != null && !isWebserviceConsumer(psiClass)) return

		logIfEnabled(project, log, "Running WSHeadersOnType validation on Kotlin ${classOrObject.name}")

		// 3. Find @WSHeaders annotation using direct Kotlin PSI
		val wsHeadersShortName = getWsHeadersFqn(project).substringAfterLast('.')
		val wsHeaderShortName = getWsHeaderFqn(project).substringAfterLast('.')

		val headersAnnotation = classOrObject.annotationEntries.find {
			it.shortName?.asString() == wsHeadersShortName
		}

		if (headersAnnotation != null) {
			// Extract headers from the value array
			val valueArg = headersAnnotation.valueArguments.find {
				it.getArgumentName()?.asName?.asString() == "value" || it.getArgumentName() == null
			}

			val arrayExpr = valueArg?.getArgumentExpression()
			if (arrayExpr is KtCollectionLiteralExpression) {
				arrayExpr.getInnerExpressions().forEach { expr ->
					if (expr is KtAnnotationEntry && expr.shortName?.asString() == wsHeaderShortName) {
						validateSingleKotlinTypeHeaderDefault(expr, classOrObject, holder, log)
					}
				}
			}
		}
	}

	/**
	 * Validates that a single @WSHeader annotation (expected to be from the type level)
	 * has a non-empty defaultValue.
	 */
	private fun validateSingleTypeHeaderDefault(
		headerAnnotation: PsiAnnotation,
		holder: ProblemsHolder,
		logger: Logger
	) {
		val headerName = getAnnotationStringAttribute(headerAnnotation, "name") ?: "[Unknown Name]"
		val defaultValueAttr = headerAnnotation.findAttributeValue("defaultValue")
		val isValid = when (defaultValueAttr) {
			is PsiLiteralExpression -> (defaultValueAttr.value as? String)?.isNotEmpty() == true
			is PsiReferenceExpression -> true // Accept constant reference
			else -> false
		}
		logIfEnabled(headerAnnotation.project, logger, "Validating type @WSHeader '$headerName' with defaultValueAttr='$defaultValueAttr'")

		if (!isValid) {
			logIfEnabled(headerAnnotation.project, logger, "ERROR: Type-level header '$headerName' has missing, empty, or invalid defaultValue.")
			// Always mark the class/interface itself
			val classOrInterface = headerAnnotation.parent?.parent?.parent as? PsiClass
			val elementToHighlight = classOrInterface?.nameIdentifier ?: classOrInterface ?: headerAnnotation
			holder.registerProblem(
				elementToHighlight,
				MyBundle.message("inspection.wsheadersontype.error.missing.defaultvalue", headerName),
				ProblemHighlightType.ERROR
			)
		}
	}

	/**
	 * K2-compatible validation for Kotlin @WSHeader annotations
	 */
	private fun validateSingleKotlinTypeHeaderDefault(
		headerAnnotation: KtAnnotationEntry,
		classOrObject: KtClassOrObject,
		holder: ProblemsHolder,
		logger: Logger
	) {
		val headerName = getKotlinAnnotationStringAttribute(headerAnnotation, "name") ?: "[Unknown Name]"

		val defaultValueArg = headerAnnotation.valueArguments.find {
			it.getArgumentName()?.asName?.asString() == "defaultValue"
		}

		val isValid = when (val expr = defaultValueArg?.getArgumentExpression()) {
			is KtStringTemplateExpression -> {
				val text = expr.text
				text.length > 2 && text.startsWith("\"") && text.endsWith("\"") && text.substring(1, text.length - 1).isNotEmpty()
			}

			is KtNameReferenceExpression -> true // Accept constant reference
			null -> false
			else -> true // Accept other expressions as potentially valid
		}

		logIfEnabled(headerAnnotation.project, logger, "Validating Kotlin type @WSHeader '$headerName' with defaultValue='${defaultValueArg?.getArgumentExpression()?.text}'")

		if (!isValid) {
			logIfEnabled(headerAnnotation.project, logger, "ERROR: Kotlin type-level header '$headerName' has missing, empty, or invalid defaultValue.")
			val elementToHighlight = classOrObject.nameIdentifier ?: classOrObject
			holder.registerProblem(
				elementToHighlight,
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
				// Removed analyze {} block - not needed for annotation checks
				// Use direct Kotlin PSI validation instead of light class conversion
				validateKotlinTypeHeaders(classOrObject.project, classOrObject, holder)
			}
		}
	}
}
