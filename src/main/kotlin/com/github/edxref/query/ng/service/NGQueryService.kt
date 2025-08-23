package com.github.edxref.query.ng.service

import com.github.edxref.query.ng.index.NGQueryUtilsIndex
import com.github.edxref.query.ng.index.NGSQLRefIndex
import com.github.edxref.query.ng.index.NGXmlQueryIndex
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.util.indexing.FileBasedIndex
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class NGQueryService(private val project: Project) {

  companion object {
    private val log = logger<NGQueryService>()

    // Limit runtime search to avoid hanging
    private const val MAX_FILES_FOR_RUNTIME_SEARCH = 100

    fun getInstance(project: Project): NGQueryService =
      project.getService(NGQueryService::class.java)
  }

  // Simple caches
  private val xmlCache = ConcurrentHashMap<String, PsiElement?>()
  private val queryUtilsCache = ConcurrentHashMap<String, List<PsiElement>>()
  private val sqlRefCache = ConcurrentHashMap<String, List<PsiElement>>()

  fun clearSQLRefCache() {
    sqlRefCache.clear()
    log.debug("SQLRef cache cleared")
  }

  fun cacheSQLRefAnnotation(refId: String, containingClass: PsiElement) {
    val existing = sqlRefCache[refId] ?: emptyList()
    if (!existing.contains(containingClass)) {
      sqlRefCache[refId] = existing + containingClass
      log.debug("Cached SQLRef annotation: $refId -> ${(containingClass as? PsiClass)?.name}")
    }
  }

  fun findXmlTagById(queryId: String): PsiElement? {
    // Check cache
    if (xmlCache.containsKey(queryId)) {
      val cached = xmlCache[queryId]
      if (cached?.isValid == true) {
        log.debug("Cache hit for XML query: $queryId")
        return cached
      }
      xmlCache.remove(queryId)
    }

    // Try index if not in dumb mode
    if (!DumbService.isDumb(project)) {
      val result = findXmlTagFromIndex(queryId)
      if (result != null) {
        xmlCache[queryId] = result
        return result
      }
    }

    // Fallback to runtime search (limited scope)
    val result = findXmlTagByRuntimeSearch(queryId)
    xmlCache[queryId] = result
    return result
  }

  fun findSQLRefAnnotations(refId: String): List<PsiElement> {
    // Check cache
    if (sqlRefCache.containsKey(refId)) {
      val cached = sqlRefCache[refId] ?: emptyList()
      if (cached.all { it.isValid }) {
        log.debug("Cache hit for SQLRef: $refId")
        return cached
      }
      sqlRefCache.remove(refId)
    }

    // Try index if not in dumb mode
    if (!DumbService.isDumb(project)) {
      val result = findSQLRefFromIndex(refId)
      if (result.isNotEmpty()) {
        // Remove duplicates before caching
        val uniqueResults = result.distinct()
        sqlRefCache[refId] = uniqueResults
        return uniqueResults
      }
    }

    // Skip runtime search for SQLRef if no index results
    log.debug("No SQLRef found in index for: $refId, skipping runtime search")
    sqlRefCache[refId] = emptyList()
    return emptyList()
  }

  fun findQueryUtilsUsages(queryId: String): List<PsiElement> {
    // Check cache
    if (queryUtilsCache.containsKey(queryId)) {
      val cached = queryUtilsCache[queryId] ?: emptyList()
      if (cached.all { it.isValid }) {
        log.debug("Cache hit for QueryUtils usage: $queryId")
        return cached
      }
      queryUtilsCache.remove(queryId)
    }

    // Try index if not in dumb mode
    if (!DumbService.isDumb(project)) {
      val result = findQueryUtilsFromIndex(queryId)
      if (result.isNotEmpty()) {
        // Remove duplicates before caching
        val uniqueResults = result.distinct()
        queryUtilsCache[queryId] = uniqueResults
        return uniqueResults
      }
    }

    // Skip runtime search for QueryUtils if no index results
    log.debug("No QueryUtils usage found in index for: $queryId, skipping runtime search")
    queryUtilsCache[queryId] = emptyList()
    return emptyList()
  }

  private fun findSQLRefFromIndex(refId: String): List<PsiElement> {
    // Use a Set to automatically prevent duplicates
    val results = mutableSetOf<PsiElement>()

    try {
      val fileIndex = FileBasedIndex.getInstance()
      val filePaths =
        fileIndex.getValues(NGSQLRefIndex.KEY, refId, GlobalSearchScope.projectScope(project))

      if (filePaths.isEmpty()) {
        return emptyList()
      }

      val settings = project.getQueryRefSettings()
      val targetAnnotationFqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }

      for (filePath in filePaths) {
        // Check for cancellation
        ProgressManager.checkCanceled()

        val virtualFile =
          com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: continue

        val psiFile =
          ApplicationManager.getApplication()
            .runReadAction(
              Computable { PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile }
            ) ?: continue

        psiFile.accept(
          object : JavaRecursiveElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
              super.visitAnnotation(annotation)

              // In smart mode, we can use qualifiedName
              if (!DumbService.isDumb(project)) {
                if (annotation.qualifiedName == targetAnnotationFqn) {
                  val refIdValue = extractRefIdValue(annotation)
                  if (refIdValue == refId) {
                    val containingClass =
                      PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java)
                    if (containingClass != null) {
                      results.add(containingClass) // Set prevents duplicates
                    }
                  }
                }
              } else {
                // Fallback to text-based check in dumb mode
                val annotationText = annotation.text
                if (annotationText.contains("SQLRef")) {
                  val refIdValue = extractRefIdValue(annotation)
                  if (refIdValue == refId) {
                    val containingClass =
                      PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java)
                    if (containingClass != null) {
                      results.add(containingClass) // Set prevents duplicates
                    }
                  }
                }
              }
            }
          }
        )
      }
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: Exception) {
      log.debug("Index lookup failed for SQLRef: $refId", e)
    }

    return results.toList()
  }

  // REMOVED: findSQLRefByRuntimeSearch - too expensive and causes hanging

  private fun extractRefIdValue(annotation: PsiAnnotation): String? {
    try {
      val settings = project.getQueryRefSettings()
      val attributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }

      val refIdAttr = annotation.findAttributeValue(attributeName)
      if (refIdAttr is PsiLiteralExpression) {
        return refIdAttr.value as? String
      }

      val defaultValue = annotation.findAttributeValue(null)
      if (defaultValue is PsiLiteralExpression) {
        return defaultValue.value as? String
      }
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: Exception) {
      log.debug("Error extracting refId value", e)
    }

    return null
  }

  private fun findXmlTagFromIndex(queryId: String): PsiElement? {
    try {
      val fileIndex = FileBasedIndex.getInstance()
      val filePaths =
        fileIndex.getValues(NGXmlQueryIndex.KEY, queryId, GlobalSearchScope.projectScope(project))

      for (filePath in filePaths) {
        ProgressManager.checkCanceled()

        val virtualFile =
          com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: continue

        val psiFile =
          ApplicationManager.getApplication()
            .runReadAction(
              Computable { PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile }
            ) ?: continue

        val rootTag = psiFile.rootTag
        if (rootTag?.name == "Queries") {
          rootTag.findSubTags("query").forEach { tag ->
            if (tag.getAttributeValue("id") == queryId) {
              log.debug("Found XML tag from index: $queryId")
              return tag
            }
          }
        }
      }
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: Exception) {
      log.debug("Index lookup failed for XML: $queryId", e)
    }
    return null
  }

  private fun findXmlTagByRuntimeSearch(queryId: String): PsiElement? {
    log.debug("Runtime search for XML query: $queryId")

    try {
      // Limited scope - only search for -queries.xml files
      val scope = GlobalSearchScope.projectScope(project)
      val xmlFiles =
        ApplicationManager.getApplication()
          .runReadAction(
            Computable {
              FilenameIndex.getAllFilesByExt(project, "xml", scope)
                .filter { it.name.endsWith("-queries.xml") }
                .take(MAX_FILES_FOR_RUNTIME_SEARCH) // Limit files to prevent hanging
            }
          )

      for (virtualFile in xmlFiles) {
        ProgressManager.checkCanceled()

        val psiFile =
          ApplicationManager.getApplication()
            .runReadAction(
              Computable { PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile }
            ) ?: continue

        val rootTag = psiFile.rootTag
        if (rootTag?.name == "Queries") {
          rootTag.findSubTags("query").forEach { tag ->
            if (tag.getAttributeValue("id") == queryId) {
              log.debug("Found XML tag by runtime search: $queryId")
              return tag
            }
          }
        }
      }
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: Exception) {
      log.debug("Runtime search failed for XML: $queryId", e)
    }

    return null
  }

  private fun findQueryUtilsFromIndex(queryId: String): List<PsiElement> {
    // Use a Set to automatically prevent duplicates
    val results = mutableSetOf<PsiElement>()

    try {
      val fileIndex = FileBasedIndex.getInstance()
      val filePaths =
        fileIndex.getValues(NGQueryUtilsIndex.KEY, queryId, GlobalSearchScope.projectScope(project))

      for (filePath in filePaths) {
        ProgressManager.checkCanceled()

        val virtualFile =
          com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: continue

        val psiFile =
          ApplicationManager.getApplication()
            .runReadAction(
              Computable { PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile }
            ) ?: continue

        psiFile.accept(
          object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
              super.visitMethodCallExpression(call)

              val methodExpr = call.methodExpression
              if (methodExpr.referenceName == "getQuery") {
                val args = call.argumentList.expressions
                if (args.isNotEmpty() && args[0] is PsiLiteralExpression) {
                  val literal = args[0] as PsiLiteralExpression
                  if (literal.value == queryId) {
                    results.add(call) // Set prevents duplicates
                  }
                }
              }
            }
          }
        )
      }
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: Exception) {
      log.debug("Index lookup failed for QueryUtils: $queryId", e)
    }

    return results.toList()
  }

  // REMOVED: findQueryUtilsByRuntimeSearch - rely on index only

  fun clearCache() {
    xmlCache.clear()
    queryUtilsCache.clear()
    sqlRefCache.clear()
    log.info("NG Query Service cache cleared")
  }
}
