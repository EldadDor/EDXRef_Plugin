package com.github.edxref.inspection

import com.github.edxref.MyBundle
import com.intellij.codeInspection.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import org.jetbrains.kotlin.psi.*

// --- Inspection Logic for Type Headers ---
interface WSHeadersOnTypeInspectionLogic {
  val log: Logger

  fun validateTypeHeaders(project: Project, psiClass: PsiClass, holder: ProblemsHolder) {
    // 1. Prerequisites
    psiClass.getAnnotation(InspectionUtil.getWsConsumerAnnotationFqn(project)) ?: return
    if (!InspectionUtil.isWebserviceConsumer(psiClass)) return

    InspectionUtil.logIfEnabled(
      project,
      log,
      "Running WSHeadersOnType validation on ${psiClass.name}",
    )

    // 2. Find @WSHeaders container and validate children
    val wsHeadersFqn = InspectionUtil.getWsHeadersFqn(project)
    val wsHeaderFqn = InspectionUtil.getWsHeaderFqn(project)
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
    holder: ProblemsHolder,
  ) {
    // 1. Check for @WSConsumer annotation using direct Kotlin PSI
    if (!InspectionUtil.hasWSConsumerAnnotation(project, classOrObject)) return

    // 2. Check if it's a webservice consumer
    if (!InspectionUtil.isKotlinWebserviceConsumer(classOrObject)) return

    InspectionUtil.logIfEnabled(
      project,
      log,
      "Running WSHeadersOnType validation on Kotlin ${classOrObject.name}",
    )

    // 3. Find @WSHeaders annotation using direct Kotlin PSI
    val wsHeadersShortName = InspectionUtil.getShortName(InspectionUtil.getWsHeadersFqn(project))
    val wsHeaderShortName = InspectionUtil.getShortName(InspectionUtil.getWsHeaderFqn(project))

    val headersAnnotation = InspectionUtil.findKotlinAnnotation(classOrObject, wsHeadersShortName)

    if (headersAnnotation != null) {
      // Extract headers from the value array
      val valueArg =
        headersAnnotation.valueArguments.find {
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
   * Validates that a single @WSHeader annotation (expected to be from the type level) has a
   * non-empty defaultValue.
   */
  private fun validateSingleTypeHeaderDefault(
    headerAnnotation: PsiAnnotation,
    holder: ProblemsHolder,
    logger: Logger,
  ) {
    val headerName =
      InspectionUtil.getAnnotationStringAttribute(headerAnnotation, "name") ?: "[Unknown Name]"
    val defaultValueAttr = headerAnnotation.findAttributeValue("defaultValue")
    val isValid =
      when (defaultValueAttr) {
        is PsiLiteralExpression -> (defaultValueAttr.value as? String)?.isNotEmpty() == true
        is PsiReferenceExpression -> true // Accept constant reference
        else -> false
      }
    InspectionUtil.logIfEnabled(
      headerAnnotation.project,
      logger,
      "Validating type @WSHeader '$headerName' with defaultValueAttr='$defaultValueAttr'",
    )

    if (!isValid) {
      InspectionUtil.logIfEnabled(
        headerAnnotation.project,
        logger,
        "ERROR: Type-level header '$headerName' has missing, empty, or invalid defaultValue.",
      )
      // Always mark the class/interface itself
      val classOrInterface = headerAnnotation.parent?.parent?.parent as? PsiClass
      val elementToHighlight =
        classOrInterface?.nameIdentifier ?: classOrInterface ?: headerAnnotation
      holder.registerProblem(
        elementToHighlight,
        MyBundle.message("inspection.wsheadersontype.error.missing.defaultvalue", headerName),
        ProblemHighlightType.ERROR,
      )
    }
  }

  /** K2-compatible validation for Kotlin @WSHeader annotations */
  private fun validateSingleKotlinTypeHeaderDefault(
    headerAnnotation: KtAnnotationEntry,
    classOrObject: KtClassOrObject,
    holder: ProblemsHolder,
    logger: Logger,
  ) {
    val headerName =
      InspectionUtil.getKotlinAnnotationStringAttribute(headerAnnotation, "name")
        ?: "[Unknown Name]"

    val defaultValueArg =
      headerAnnotation.valueArguments.find {
        it.getArgumentName()?.asName?.asString() == "defaultValue"
      }

    val isValid =
      when (val expr = defaultValueArg?.getArgumentExpression()) {
        is KtStringTemplateExpression -> {
          val text = expr.text
          text.length > 2 &&
            text.startsWith("\"") &&
            text.endsWith("\"") &&
            text.substring(1, text.length - 1).isNotEmpty()
        }
        is KtNameReferenceExpression -> true // Accept constant reference
        null -> false
        else -> true // Accept other expressions as potentially valid
      }

    InspectionUtil.logIfEnabled(
      headerAnnotation.project,
      logger,
      "Validating Kotlin type @WSHeader '$headerName' with defaultValue='${defaultValueArg?.getArgumentExpression()?.text}'",
    )

    if (!isValid) {
      InspectionUtil.logIfEnabled(
        headerAnnotation.project,
        logger,
        "ERROR: Kotlin type-level header '$headerName' has missing, empty, or invalid defaultValue.",
      )
      val elementToHighlight = classOrObject.nameIdentifier ?: classOrObject
      holder.registerProblem(
        elementToHighlight,
        MyBundle.message("inspection.wsheadersontype.error.missing.defaultvalue", headerName),
        ProblemHighlightType.ERROR,
      )
    }
  }
}

// --- Java Inspection ---
class WSHeadersOnTypeJavaInspection :
  AbstractBaseJavaLocalInspectionTool(), WSHeadersOnTypeInspectionLogic {
  override val log = logger<WSHeadersOnTypeJavaInspection>()

  override fun getDisplayName(): String = MyBundle.message("inspection.wsheadersontype.displayname")

  override fun buildVisitor(
    holder: ProblemsHolder,
    isOnTheFly: Boolean,
    session: LocalInspectionToolSession,
  ): PsiElementVisitor {
    return object : JavaElementVisitor() {
      override fun visitClass(psiClass: PsiClass) {
        super.visitClass(psiClass)
        validateTypeHeaders(psiClass.project, psiClass, holder)
      }
    }
  }
}

// --- Kotlin Inspection ---
class WSHeadersOnTypeKotlinInspection :
  AbstractBaseUastLocalInspectionTool(), WSHeadersOnTypeInspectionLogic {
  override val log = logger<WSHeadersOnTypeKotlinInspection>()

  override fun getDisplayName(): String = MyBundle.message("inspection.wsheadersontype.displayname")

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : KtVisitorVoid() {
      override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        // K2-compatible validation using direct Kotlin PSI
        validateKotlinTypeHeaders(classOrObject.project, classOrObject, holder)
      }
    }
  }
}
