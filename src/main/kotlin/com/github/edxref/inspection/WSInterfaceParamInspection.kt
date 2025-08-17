package com.github.edxref.inspection

import com.github.edxref.MyBundle
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.codeInspection.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.psi.*
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.uast.UClass
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor


// --- Settings Accessors (Keep relevant ones or move to util) ---
private fun getSettings(project: Project) = project.getWSConsumerSettings()
private fun getWsConsumerAnnotationFqn(project: Project) = getSettings(project).wsConsumerAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSConsumer" }
private fun getWebserviceConsumerFqn(project: Project) = getSettings(project).webserviceConsumerFqn.ifBlank { "com.github.edxref.annotations.WebserviceConsumer" }
private fun getPropertyFqn(project: Project) = getSettings(project).propertyAnnotationFqn.ifBlank { "com.github.edxref.annotations.Property" }
private fun getWsParamFqn(project: Project) = getSettings(project).wsParamAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSParam" }
private fun logIfEnabled(project: Project, logger: Logger, message: String) {
	try {
		if (getSettings(project).enableLog) logger.info(message)
	} catch (e: Exception) { /* ignore */
	}
}

fun isImplementingInterface(psiClass: PsiClass, interfaceFqn: String): Boolean {
	if (interfaceFqn.isBlank()) return false
	return InheritanceUtil.isInheritor(psiClass, interfaceFqn)
}

// --- Interface Inspection Logic (Specific Rules) ---
interface WSInterfaceSpecificLogic {
	val log: Logger // Still needed for specific logging

	fun runInterfaceSpecificValidations(
		project: Project,
		psiClass: PsiClass,
		wsConsumerAnnotation: PsiAnnotation,
		holder: ProblemsHolder
	) {
		// 1. Validate @Property on setters/getters if enabled
		validatePropertyAnnotationsIfEnabled(project, psiClass, holder)

		// 2. Validate HTTP method and associated getter rules (isBodyParam)
		validateHttpMethodAndGetters(project, wsConsumerAnnotation, psiClass, holder)
	}

	// --- Private Helpers for Interface Specific Logic ---

	private fun validatePropertyAnnotationsIfEnabled(
		project: Project,
		psiClass: PsiClass,
		holder: ProblemsHolder
	) {
		if (!getSettings(project).validatePropertyAnnotations) {
			logIfEnabled(project, log, "Skipping @Property validation as per settings.")
			return
		}

		val propertyAnnotationFqn = getPropertyFqn(project)
		val propertyAnnotationShortName = propertyAnnotationFqn.substringAfterLast('.')

		for (method in psiClass.methods) { // Only check methods directly in interface
			val isSetter = method.name.startsWith("set") && method.parameterList.parametersCount == 1
			val isGetter = method.name.startsWith("get") && method.parameterList.parametersCount == 0

			if ((isSetter || isGetter) && method.getAnnotation(propertyAnnotationFqn) == null) {
				logIfEnabled(project, log, "ERROR: Method '${method.name}' is missing the @$propertyAnnotationShortName annotation.")

				// For K2 compatibility, ensure we highlight the correct element
				val elementToHighlight = method.nameIdentifier ?: method
				holder.registerProblem(
					elementToHighlight,
					MyBundle.message("inspection.wsinterfaceparam.error.missing.property.annotation", method.name, propertyAnnotationShortName),
					ProblemHighlightType.ERROR
				)
			}
		}
	}


	private fun validateHttpMethodAndGetters(
		project: Project,
		wsConsumerAnnotation: PsiAnnotation,
		psiClass: PsiClass,
		holder: ProblemsHolder
	) {
		val methodAttrValue = wsConsumerAnnotation.findAttributeValue("method")
		val methodValue = if (methodAttrValue is PsiReferenceExpression) methodAttrValue.referenceName else null

		if (methodValue in listOf("GET", "POST", "PUT")) {
			logIfEnabled(project, log, "Method is $methodValue for ${psiClass.name}, validating getters.")
			validateGettersForBodyParam(project, psiClass, wsConsumerAnnotation, holder)
		}
	}

	private fun validateGettersForBodyParam(
		project: Project,
		psiClass: PsiClass,
		wsConsumerAnnotation: PsiAnnotation,
		holder: ProblemsHolder
	) {
		val wsParamFqn = getWsParamFqn(project)
		val getters = psiClass.methods.filter { it.name.startsWith("get") && it.parameterList.parametersCount == 0 }

		if (getters.isNotEmpty()) {
			val bodyParamGetters = getters.filter { method ->
				val wsParamAnnotation = method.getAnnotation(wsParamFqn)
				val isBodyParamAttr = wsParamAnnotation?.findAttributeValue("isBodyParam")
				(isBodyParamAttr is PsiLiteralExpression && isBodyParamAttr.value == true) ||
						isBodyParamAttr?.text == "true"
			}

			// K2-compatible element highlighting - prefer specific attributes over class
			val highlightElementForClassError = wsConsumerAnnotation.findAttributeValue("method")
				?: psiClass.nameIdentifier
				?: psiClass

			when (bodyParamGetters.size) {
				0 -> {
					logIfEnabled(project, log, "ERROR: Getters exist but none marked with isBodyParam=true in ${psiClass.name}")
					holder.registerProblem(
						highlightElementForClassError,
						MyBundle.message("inspection.wsinterfaceparam.error.bodyparam.required"),
						ProblemHighlightType.ERROR
					)
				}

				1 -> logIfEnabled(project, log, "OK: Found exactly one getter marked with isBodyParam=true in ${psiClass.name}")

				else -> {
					logIfEnabled(project, log, "ERROR: Multiple getters marked with isBodyParam=true in ${psiClass.name}")
					holder.registerProblem(
						highlightElementForClassError,
						MyBundle.message("inspection.wsinterfaceparam.error.multiple.bodyparam"),
						ProblemHighlightType.ERROR
					)
					bodyParamGetters.forEach { getter ->
						val getterHighlight = getter.nameIdentifier ?: getter
						holder.registerProblem(
							getterHighlight,
							MyBundle.message("inspection.wsinterfaceparam.error.multiple.bodyparam"),
							ProblemHighlightType.ERROR
						)
					}
				}
			}
		} else {
			logIfEnabled(project, log, "OK: No getters found in ${psiClass.name}, skipping isBodyParam check.")
		}
	}

}

// --- Combined Interface for Implementation ---
interface FullWSInterfaceValidationLogic : WSParamValidationLogic, WSInterfaceSpecificLogic

// --- Java Inspection ---
class WSInterfaceParamJavaInspection : AbstractBaseJavaLocalInspectionTool(), FullWSInterfaceValidationLogic {
	override val log = logger<WSInterfaceParamJavaInspection>() // Provide logger
	override fun getDisplayName(): String = MyBundle.message("inspection.wsinterfaceparam.displayname")

	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
		return object : JavaElementVisitor() {
			override fun visitClass(psiClass: PsiClass) {
				super.visitClass(psiClass)
				if (!psiClass.isInterface) return // Only interfaces

				val project = psiClass.project
				val wsConsumerAnnotation = psiClass.getAnnotation(getWsConsumerAnnotationFqn(project)) ?: return
				if (!isImplementingInterface(psiClass, getWebserviceConsumerFqn(project))) return

				// Run interface-specific validations
				runInterfaceSpecificValidations(project, psiClass, wsConsumerAnnotation, holder)

				// Run shared URL parameter validations
				val urlAttrValue = wsConsumerAnnotation.findAttributeValue("url")
				val urlValue = if (urlAttrValue is PsiLiteralExpression && urlAttrValue.value is String) urlAttrValue.value as String else null
				val urlParams = extractUrlParameters(urlValue) // Use shared helper
				validateUrlParamsAgainstSetters(project, urlParams, urlAttrValue, psiClass.methods, wsConsumerAnnotation, holder) // Use shared logic
			}
		}
	}
}

// --- Kotlin Inspection ---
class WSInterfaceParamKotlinInspection : AbstractBaseUastLocalInspectionTool(), FullWSInterfaceValidationLogic {
	override val log = logger<WSInterfaceParamKotlinInspection>()
	override fun getDisplayName(): String = MyBundle.message("inspection.wsinterfaceparam.displayname")

	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		return UastHintedVisitorAdapter.create(
			holder.file.language,
			object : AbstractUastNonRecursiveVisitor() {
				override fun visitClass(node: UClass): Boolean {
					val sourcePsi = node.sourcePsi
					if (sourcePsi is KtClass && sourcePsi.isInterface()) {
						val psiClass = node.javaPsi
						val project = sourcePsi.project

						// Check for @WSConsumer annotation
						val wsConsumerAnnotation = psiClass.getAnnotation(getWsConsumerAnnotationFqn(project))
							?: return false

						if (!isImplementingInterface(psiClass, getWebserviceConsumerFqn(project)))
							return false

						// Run interface-specific validations
						runInterfaceSpecificValidations(project, psiClass, wsConsumerAnnotation, holder)

						// Run shared URL parameter validations
						val urlAttrValue = wsConsumerAnnotation.findAttributeValue("url")
						val urlValue = if (urlAttrValue is PsiLiteralExpression && urlAttrValue.value is String)
							urlAttrValue.value as String else null
						val urlParams = extractUrlParameters(urlValue)
						validateUrlParamsAgainstSetters(project, urlParams, urlAttrValue, psiClass.methods, wsConsumerAnnotation, holder)
					}
					return false // Don't visit children, we handle everything here
				}
			},
			arrayOf(UClass::class.java)
		)
	}
}