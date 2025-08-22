package com.github.edxref.query.ng.service

import com.github.edxref.query.ng.index.NGQueryUtilsIndex
import com.github.edxref.query.ng.index.NGSQLRefIndex
import com.github.edxref.query.ng.index.NGXmlQueryIndex
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
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

    fun getInstance(project: Project): NGQueryService =
      project.getService(NGQueryService::class.java)
  }

  // Simple caches
  private val xmlCache = ConcurrentHashMap<String, PsiElement?>()
  private val queryUtilsCache = ConcurrentHashMap<String, List<PsiElement>>()
  private val sqlRefCache = ConcurrentHashMap<String, List<PsiElement>>()

  /** Clear only the SQLRef cache */
  fun clearSQLRefCache() {
    sqlRefCache.clear()
    log.debug("SQLRef cache cleared")
  }

  /** Cache a validated SQLRef annotation */
  fun cacheSQLRefAnnotation(refId: String, containingClass: PsiElement) {
    val existing = sqlRefCache[refId] ?: emptyList()
    if (!existing.contains(containingClass)) {
      sqlRefCache[refId] = existing + containingClass
      log.debug("Cached SQLRef annotation: $refId -> ${(containingClass as? PsiClass)?.name}")
    }
  }

  /** Find XML tag by query ID */
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

    // Fallback to runtime search
    val result = findXmlTagByRuntimeSearch(queryId)
    xmlCache[queryId] = result
    return result
  }

  /** Find SQLRef annotations by refId */
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
        sqlRefCache[refId] = result
        return result
      }
    }

    // Fallback to runtime search
    val result = findSQLRefByRuntimeSearch(refId)
    sqlRefCache[refId] = result
    return result
  }

  /** Find QueryUtils usages by query ID */
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
        queryUtilsCache[queryId] = result
        return result
      }
    }

    // Fallback to runtime search
    val result = findQueryUtilsByRuntimeSearch(queryId)
    queryUtilsCache[queryId] = result
    return result
  }

  private fun findSQLRefFromIndex(refId: String): List<PsiElement> {
    val results = mutableListOf<PsiElement>()

    try {
      val fileIndex = FileBasedIndex.getInstance()
      val filePaths =
        fileIndex.getValues(NGSQLRefIndex.KEY, refId, GlobalSearchScope.projectScope(project))

      val settings = project.getQueryRefSettings()
      val targetAnnotationFqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }

      for (filePath in filePaths) {
        val virtualFile =
          com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: continue
        val psiFile =
          PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: continue

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
                    if (containingClass != null && !results.contains(containingClass)) {
                      results.add(containingClass)
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
                    if (containingClass != null && !results.contains(containingClass)) {
                      results.add(containingClass)
                    }
                  }
                }
              }
            }
          }
        )
      }
    } catch (e: ProcessCanceledException) {
      // Rethrow ProcessCanceledException - never log it
      throw e
    } catch (e: Exception) {
      log.debug("Index lookup failed for SQLRef: $refId", e)
    }

    return results
  }

  private fun findSQLRefByRuntimeSearch(refId: String): List<PsiElement> {
    log.debug("Runtime search for SQLRef: $refId")
    val results = mutableListOf<PsiElement>()

    try {
      val settings = project.getQueryRefSettings()
      val targetAnnotationFqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }

      // Search in all Java files
      val javaFiles = FilenameIndex.getAllFilesByExt(project, "java")

      for (virtualFile in javaFiles) {
        val psiFile =
          PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: continue

        psiFile.accept(
          object : JavaRecursiveElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
              super.visitAnnotation(annotation)

              // Try to match by qualified name if possible
              val isMatch =
                if (!DumbService.isDumb(project)) {
                  annotation.qualifiedName == targetAnnotationFqn
                } else {
                  // Fallback to text matching in dumb mode
                  annotation.text.contains("SQLRef")
                }

              if (isMatch) {
                val refIdValue = extractRefIdValue(annotation)
                if (refIdValue == refId) {
                  val containingClass =
                    PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java)
                  if (containingClass != null && !results.contains(containingClass)) {
                    results.add(containingClass)
                  }
                }
              }
            }
          }
        )
      }
    } catch (e: ProcessCanceledException) {
      // Rethrow ProcessCanceledException - never log it
      throw e
    } catch (e: Exception) {
      // Only log non-control-flow exceptions
      log.debug("Runtime search failed for SQLRef: $refId", e)
    }

    return results
  }

  private fun extractRefIdValue(annotation: PsiAnnotation): String? {
    try {
      val settings = project.getQueryRefSettings()
      val attributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }

      // Try to get refId attribute value
      val refIdAttr = annotation.findAttributeValue(attributeName)
      if (refIdAttr is PsiLiteralExpression) {
        return refIdAttr.value as? String
      }

      // If it's the default value (single value annotation)
      val defaultValue = annotation.findAttributeValue(null)
      if (defaultValue is PsiLiteralExpression) {
        return defaultValue.value as? String
      }
    } catch (e: ProcessCanceledException) {
      // Rethrow ProcessCanceledException
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
        val virtualFile =
          com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: continue
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile ?: continue

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
      // Rethrow ProcessCanceledException
      throw e
    } catch (e: Exception) {
      log.debug("Index lookup failed for XML: $queryId", e)
    }
    return null
  }

  private fun findXmlTagByRuntimeSearch(queryId: String): PsiElement? {
    log.debug("Runtime search for XML query: $queryId")

    try {
      val xmlFiles =
        FilenameIndex.getAllFilesByExt(project, "xml").filter { it.name.endsWith("-queries.xml") }

      for (virtualFile in xmlFiles) {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile ?: continue

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
      // Rethrow ProcessCanceledException
      throw e
    } catch (e: Exception) {
      // Only log non-control-flow exceptions
      log.debug("Runtime search failed for XML: $queryId", e)
    }

    return null
  }

  private fun findQueryUtilsFromIndex(queryId: String): List<PsiElement> {
    val results = mutableListOf<PsiElement>()

    try {
      val fileIndex = FileBasedIndex.getInstance()
      val filePaths =
        fileIndex.getValues(NGQueryUtilsIndex.KEY, queryId, GlobalSearchScope.projectScope(project))

      for (filePath in filePaths) {
        val virtualFile =
          com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: continue
        val psiFile =
          PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: continue

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
                    results.add(call)
                  }
                }
              }
            }
          }
        )
      }
    } catch (e: ProcessCanceledException) {
      // Rethrow ProcessCanceledException
      throw e
    } catch (e: Exception) {
      log.debug("Index lookup failed for QueryUtils: $queryId", e)
    }

    return results
  }

  private fun findQueryUtilsByRuntimeSearch(queryId: String): List<PsiElement> {
    log.debug("Runtime search for QueryUtils usage: $queryId")
    val results = mutableListOf<PsiElement>()

    try {
      val javaFiles = FilenameIndex.getAllFilesByExt(project, "java")

      for (virtualFile in javaFiles) {
        val psiFile =
          PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: continue

        psiFile.accept(
          object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
              super.visitMethodCallExpression(call)

              val methodExpr = call.methodExpression
              if (methodExpr.referenceName == "getQuery") {
                val qualifierText = methodExpr.qualifierExpression?.text?.lowercase()
                if (qualifierText?.contains("queryutils") == true) {
                  val args = call.argumentList.expressions
                  if (args.isNotEmpty() && args[0] is PsiLiteralExpression) {
                    val literal = args[0] as PsiLiteralExpression
                    if (literal.value == queryId) {
                      results.add(call)
                    }
                  }
                }
              }
            }
          }
        )
      }
    } catch (e: ProcessCanceledException) {
      // Rethrow ProcessCanceledException
      throw e
    } catch (e: Exception) {
      // Only log non-control-flow exceptions
      log.debug("Runtime search failed for QueryUtils: $queryId", e)
    }

    return results
  }

  fun clearCache() {
    xmlCache.clear()
    queryUtilsCache.clear()
    sqlRefCache.clear()
    log.info("NG Query Service cache cleared")
  }
}
