package com.github.edxref.query.ng.service

/*
 * User: eadno1
 * Date: 21/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */

import com.github.edxref.query.ng.index.NGQueryUtilsIndex
import com.github.edxref.query.ng.index.NGXmlQueryIndex
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
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

  // Simple caches - no weak references for simplicity
  private val xmlCache = ConcurrentHashMap<String, PsiElement?>()
  private val queryUtilsCache = ConcurrentHashMap<String, List<PsiElement>>()

  /**
   * Find XML tag by query ID
   * 1. Check cache
   * 2. Try index
   * 3. Fallback to runtime search
   */
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

  /**
   * Find QueryUtils usages by query ID
   * 1. Check cache
   * 2. Try index
   * 3. Fallback to runtime search
   */
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
    } catch (e: Exception) {
      log.error("Index lookup failed for XML: $queryId", e)
    }
    return null
  }

  private fun findXmlTagByRuntimeSearch(queryId: String): PsiElement? {
    log.debug("Runtime search for XML query: $queryId")

    try {
      // Find all -queries.xml files
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
    } catch (e: Exception) {
      log.error("Runtime search failed for XML: $queryId", e)
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
    } catch (e: Exception) {
      log.error("Index lookup failed for QueryUtils: $queryId", e)
    }

    return results
  }

  private fun findQueryUtilsByRuntimeSearch(queryId: String): List<PsiElement> {
    log.debug("Runtime search for QueryUtils usage: $queryId")
    val results = mutableListOf<PsiElement>()

    try {
      // Search in all Java files
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
    } catch (e: Exception) {
      log.error("Runtime search failed for QueryUtils: $queryId", e)
    }

    return results
  }

  fun clearCache() {
    xmlCache.clear()
    queryUtilsCache.clear()
    log.info("NG Query Service cache cleared")
  }
}
