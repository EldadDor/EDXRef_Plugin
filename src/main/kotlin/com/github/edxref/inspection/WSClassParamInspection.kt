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
import org.jetbrains.kotlin.psi.*

// --- Settings Accessors (Copied or move to Util) ---
private fun getSettings(project: Project) = project.getWSConsumerSettings()
private fun getWsConsumerAnnotationFqn(project: Project) = getSettings(project).wsConsumerAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSConsumer" }
private fun getPearlWebserviceConsumerFqn(project: Project) = getSettings(project).pearlWebserviceConsumerFqn.ifBlank { "com.github.edxref.annotations.PearlWebserviceConsumer" }
private fun getHttpRequestAnnotationFqn(project: Project) = getSettings(project).httpRequestAnnotationFqn.ifBlank { "com.github.edxref.annotations.HttpRequest" }
private fun logIfEnabled(project: Project, logger: Logger, message: String) {
	try {
		if (getSettings(project).enableLog) logger.info(message)
	} catch (e: Exception) { /* ignore */
	}
}

// isImplementingClass helper (similar to interface one)
private fun isExtendingClass(psiClass: PsiClass, classFqn: String): Boolean {
	if (classFqn.isBlank()) return false
	return InheritanceUtil.isInheritor(psiClass, classFqn)
}

// --- Class Inspection Logic (Implements shared logic) ---
interface WSClassParamInspectionLogic : WSParamValidationLogic { // Implement shared interface

	fun runClassSpecificValidations(
		project: Project,
		psiClass: PsiClass,
		wsConsumerAnnotation: PsiAnnotation,
		holder: ProblemsHolder
	) {
		// 1. Check for @HttpRequest annotation
		val httpRequestFqn = getHttpRequestAnnotationFqn(project)
		if (psiClass.getAnnotation(httpRequestFqn) == null) {
			logIfEnabled(project, log, "ERROR: Class ${psiClass.name} is missing @${httpRequestFqn.substringAfterLast('.')}")
			holder.registerProblem(
				psiClass.nameIdentifier ?: psiClass, // Highlight class name
				MyBundle.message("inspection.wsclassparam.error.missing.httprequest"),
				ProblemHighlightType.ERROR
			)
		}

		// Add other class-specific validations here if needed in the future
	}
}

// --- Java Inspection ---
class WSClassParamJavaInspection : AbstractBaseJavaLocalInspectionTool(), WSClassParamInspectionLogic {
	override val log = logger<WSClassParamJavaInspection>() // Provide logger
	override fun getDisplayName(): String = MyBundle.message("inspection.wsclassparam.displayname")

	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
		return object : JavaElementVisitor() {
			override fun visitClass(psiClass: PsiClass) {
				super.visitClass(psiClass)
				if (psiClass.isInterface) return // Only classes

				val project = psiClass.project
				val wsConsumerAnnotation = psiClass.getAnnotation(getWsConsumerAnnotationFqn(project)) ?: return
				// Check if it extends PearlWebserviceConsumer
				if (!isExtendingClass(psiClass, getPearlWebserviceConsumerFqn(project))) return

				// Run class-specific validations
				runClassSpecificValidations(project, psiClass, wsConsumerAnnotation, holder)

				// Run shared URL parameter validations
				val urlAttrValue = wsConsumerAnnotation.findAttributeValue("url")
				val urlValue = if (urlAttrValue is PsiLiteralExpression && urlAttrValue.value is String) urlAttrValue.value as String else null
				val urlParams = extractUrlParameters(urlValue) // Use shared helper
				// Use psiClass.allMethods if inherited setters should be included, otherwise psiClass.methods
				validateUrlParamsAgainstSetters(project, urlParams, urlAttrValue, psiClass.allMethods, wsConsumerAnnotation, holder) // Use shared logic
			}
		}
	}
}

// --- Kotlin Inspection ---
class WSClassParamKotlinInspection : AbstractBaseUastLocalInspectionTool
	(), WSClassParamInspectionLogic {
	override val log = logger<WSClassParamKotlinInspection>()
	override fun getDisplayName(): String = MyBundle.message("inspection.wsclassparam.displayname")

	override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
		return object : KtVisitorVoid() {
			override fun visitClassOrObject(classOrObject: KtClassOrObject) {
				super.visitClassOrObject(classOrObject)

				// Remove the analyze {} block and use direct PSI operations instead
				if (classOrObject is KtClass && !classOrObject.isInterface()) {
					val psiClass = classOrObject.toLightClass() ?: return
					val project = classOrObject.project

					val wsConsumerAnnotation = psiClass.getAnnotation(getWsConsumerAnnotationFqn(project)) ?: return
					if (!isExtendingClass(psiClass, getPearlWebserviceConsumerFqn(project))) return

					runClassSpecificValidations(project, psiClass, wsConsumerAnnotation, holder)

					val urlAttrValue = wsConsumerAnnotation.findAttributeValue("url")
					val urlValue = if (urlAttrValue is PsiLiteralExpression && urlAttrValue.value is String) urlAttrValue.value as String else null
					val urlParams = extractUrlParameters(urlValue)
					validateUrlParamsAgainstSetters(project, urlParams, urlAttrValue, psiClass.allMethods, wsConsumerAnnotation, holder)
				}
			}
		}
	}
}

