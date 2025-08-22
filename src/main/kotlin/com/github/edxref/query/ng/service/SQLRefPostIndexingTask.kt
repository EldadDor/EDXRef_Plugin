package com.github.edxref.query.ng.service

import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Post-indexing task to validate SQLRef annotations after smart mode is available Uses the modern
 * ProjectActivity approach
 */
class SQLRefPostIndexingTask : ProjectActivity {

  companion object {
    private val log = logger<SQLRefPostIndexingTask>()
  }

  override suspend fun execute(project: Project) {
    // Wait for smart mode before doing anything that requires index access
    withContext(Dispatchers.IO) {
      // Register a listener for smart mode
      DumbService.getInstance(project).runWhenSmart {
        // Now we're in smart mode, we can validate annotations
        ApplicationManager.getApplication().executeOnPooledThread {
          try {
            validateSQLRefAnnotations(project)
          } catch (e: Exception) {
            log.error("Error validating SQLRef annotations", e)
          }
        }
      }
    }
  }

  private fun validateSQLRefAnnotations(project: Project) {
    log.debug("Starting SQLRef annotation validation in smart mode")

    ApplicationManager.getApplication().runReadAction {
      val service = NGQueryService.getInstance(project)
      val settings = project.getQueryRefSettings()
      val targetAnnotationFqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }

      // Clear and rebuild the SQLRef cache with validated entries
      service.clearCache()

      try {
        val javaFiles =
          FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))

        for (virtualFile in javaFiles) {
          val psiFile =
            PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: continue

          psiFile.accept(
            object : JavaRecursiveElementVisitor() {
              override fun visitAnnotation(annotation: PsiAnnotation) {
                super.visitAnnotation(annotation)

                // Now we can safely use qualifiedName since we're in smart mode
                if (annotation.qualifiedName == targetAnnotationFqn) {
                  val refIdValue = extractRefIdValue(annotation)
                  if (!refIdValue.isNullOrBlank()) {
                    val containingClass =
                      PsiTreeUtil.getParentOfType(annotation, com.intellij.psi.PsiClass::class.java)
                    if (containingClass != null) {
                      // Cache the validated result
                      service.cacheSQLRefAnnotation(refIdValue, containingClass)
                    }
                  }
                }
              }
            }
          )
        }

        log.debug("SQLRef annotation validation completed")
      } catch (e: Exception) {
        log.error("Error during SQLRef validation", e)
      }
    }
  }

  private fun extractRefIdValue(annotation: PsiAnnotation): String? {
    val refIdAttr = annotation.findAttributeValue("refId")
    if (refIdAttr is com.intellij.psi.PsiLiteralExpression) {
      return refIdAttr.value as? String
    }

    // Check default value
    val defaultValue = annotation.findAttributeValue(null)
    if (defaultValue is com.intellij.psi.PsiLiteralExpression) {
      return defaultValue.value as? String
    }

    return null
  }
}
