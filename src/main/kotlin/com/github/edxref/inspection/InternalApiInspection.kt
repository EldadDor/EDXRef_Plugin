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
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

// --- Reusable Utilities (Copied or move to Util) ---
private fun getSettings(project: Project) = project.getWSConsumerSettings()

private fun getInternalApiAnnotationFqn(project: Project) =
  getSettings(project).internalApiAnnotationFqn.ifBlank {
    "com.github.edxref.annotations.InternalApi"
  }

private fun logIfEnabled(project: Project, logger: Logger, message: String) {
  try {
    if (getSettings(project).enableLog) logger.info(message)
  } catch (e: Exception) {
    /* ignore */
  }
}

// --- End Reusable Utilities ---

// --- Inspection Logic ---
interface InternalApiInspectionLogic {
  val log: Logger

  /**
   * Rule 1: Check if @InternalApi is incorrectly placed on an interface. This is checked when
   * visiting the annotation itself.
   */
  fun checkAnnotationPlacement(
    project: Project,
    annotationElement: PsiElement, // PsiAnnotation or KtAnnotationEntry
    annotatedElement: PsiElement?, // PsiClass or KtClassOrObject
    holder: ProblemsHolder,
  ) {
    if (annotatedElement == null) return

    val internalApiFqn = getInternalApiAnnotationFqn(project)
    val annotationNameMatches =
      when (annotationElement) {
        is PsiAnnotation -> annotationElement.hasQualifiedName(internalApiFqn)
        is KtAnnotationEntry -> {
          // Basic check, refine with type resolution if needed
          annotationElement.shortName?.asString() == internalApiFqn.substringAfterLast('.')
        }

        else -> false
      }
    if (!annotationNameMatches) return

    val isInterface =
      when (annotatedElement) {
        is PsiClass -> annotatedElement.isInterface
        is KtClass -> annotatedElement.isInterface()
        else -> false
      }

    if (isInterface) {
      logIfEnabled(
        project,
        log,
        "ERROR: @InternalApi found on interface '${(annotatedElement as? PsiNamedElement)?.name}'",
      )
      holder.registerProblem(
        annotationElement, // Highlight the annotation itself
        MyBundle.message("inspection.internalapi.error.oninterface"),
        ProblemHighlightType.ERROR,
      )
    }
  }

  /**
   * Rule 2: Check if a class implements an interface incorrectly marked with @InternalApi. This is
   * checked when visiting the class.
   */
  fun checkClassImplementation(
    project: Project,
    psiClass: PsiClass, // Use PsiClass (light class for Kotlin) for hierarchy checks
    elementToHighlight: PsiElement, // Original KtClass or PsiClass name identifier
    holder: ProblemsHolder,
  ) {
    if (psiClass.isInterface) return // Only check classes

    val internalApiFqn = getInternalApiAnnotationFqn(project)
    var foundBadInterface: PsiClass? = null

    // Process all super types (interfaces)
    InheritanceUtil.processSupers(psiClass, true) { superClass ->
      // Check if the super type is an interface and has the annotation
      if (superClass.isInterface && superClass.hasAnnotation(internalApiFqn)) {
        foundBadInterface = superClass
        return@processSupers false // Stop processing once found
      }
      true // Continue processing
    }

    if (foundBadInterface != null) {
      logIfEnabled(
        project,
        log,
        "ERROR: Class '${psiClass.name}' implements interface '${foundBadInterface!!.name}' which has @InternalApi",
      )
      holder.registerProblem(
        elementToHighlight, // Highlight the class name
        MyBundle.message(
          "inspection.internalapi.error.implementsannotatedinterface",
          foundBadInterface!!.name ?: "unknown interface",
        ),
        ProblemHighlightType.ERROR,
      )
    }
  }
}

// --- Java Inspection ---
class InternalApiJavaInspection :
  AbstractBaseJavaLocalInspectionTool(), InternalApiInspectionLogic {
  override val log = logger<InternalApiJavaInspection>()

  override fun getDisplayName(): String = MyBundle.message("inspection.internalapi.displayname")

  override fun getStaticDescription(): String =
    MyBundle.message("inspection.internalapi.description")

  override fun buildVisitor(
    holder: ProblemsHolder,
    isOnTheFly: Boolean,
    session: LocalInspectionToolSession,
  ): PsiElementVisitor {
    return object : JavaElementVisitor() {
      // Rule 1 Check: Visit the annotation
      override fun visitAnnotation(annotation: PsiAnnotation) {
        super.visitAnnotation(annotation)
        val modifierList = annotation.parent as? PsiModifierList
        val annotatedElement = modifierList?.parent // Could be PsiClass, PsiMethod, etc.
        if (annotatedElement is PsiClass) { // Only care if it's on a class/interface
          checkAnnotationPlacement(annotation.project, annotation, annotatedElement, holder)
        }
      }

      // Rule 2 Check: Visit the class
      override fun visitClass(psiClass: PsiClass) {
        super.visitClass(psiClass)
        checkClassImplementation(
          psiClass.project,
          psiClass,
          psiClass.nameIdentifier ?: psiClass,
          holder,
        )
      }
    }
  }
}

// --- Kotlin Inspection ---
class InternalApiKotlinInspection :
  AbstractBaseUastLocalInspectionTool(), InternalApiInspectionLogic {
  override val log = logger<InternalApiKotlinInspection>()

  override fun getDisplayName(): String = MyBundle.message("inspection.internalapi.displayname")

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return object : KtVisitorVoid() {
      override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        val declaration = annotationEntry.getStrictParentOfType<KtDeclaration>()
        if (declaration is KtClassOrObject) {
          checkAnnotationPlacement(annotationEntry.project, annotationEntry, declaration, holder)
        }
      }

      override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)

        // For K2 compatibility, use light class for hierarchy checks when needed
        val psiClass = classOrObject.toLightClass()
        if (psiClass != null) {
          checkClassImplementation(
            classOrObject.project,
            psiClass,
            classOrObject.nameIdentifier ?: classOrObject,
            holder,
          )
        } else {
          logIfEnabled(
            classOrObject.project,
            log,
            "Could not get light class for Kotlin element ${classOrObject.name}",
          )
        }
      }
    }
  }
}
