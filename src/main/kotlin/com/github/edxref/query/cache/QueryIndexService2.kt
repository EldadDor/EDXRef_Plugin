package com.github.edxref.query.cache

/*
 * User: eadno1
 * Date: 21/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */

import com.github.edxref.query.index.SQLQueryFileIndexer
import com.github.edxref.query.index.SQLRefAnnotationIndex
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.util.indexing.FileBasedIndex
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.idea.testIntegration.framework.KotlinPsiBasedTestFramework.Companion.asKtClassOrObject

@Service(Service.Level.PROJECT)
class QueryIndexService2(private val project: Project) {

  companion object {
    private val log = logger<QueryIndexService>()
    private const val MAX_CACHE_SIZE = 5000
    private const val BATCH_SIZE = 50

    fun getInstance(project: Project): QueryIndexService =
      project.getService(QueryIndexService::class.java)
  }

  // Memory-efficient caches with weak references
  private val xmlTagCache = ConcurrentHashMap<String, WeakReference<PsiElement>>()
  private val interfaceCache = ConcurrentHashMap<String, WeakReference<PsiElement>>()
  private val queryUtilsUsageCache = ConcurrentHashMap<String, WeakReference<List<PsiElement>>>()

  @Volatile private var cacheHits = 0
  @Volatile private var cacheMisses = 0

  /** Safe index access with proper dumb mode handling */
  private fun <T> safeIndexAccess(operation: () -> T): T? {
    return try {
      if (DumbService.isDumb(project)) {
        log.debug("Skipping index access - project is in dumb mode")
        return null
      }

      // Use DumbService.getInstance to ensure we're not in dumb mode
      DumbService.getInstance(project).runReadActionInSmartMode(Computable { operation() })
    } catch (e: IndexNotReadyException) {
      log.debug("Index not ready, deferring operation: ${e.message}")
      null
    } catch (e: Exception) {
      log.warn("Error during index access", e)
      null
    }
      as T?
  }

  /** Batch operation to find multiple XML tags by their IDs Now with proper dumb mode handling */
  fun findMultipleXmlTagsById(queryIds: Set<String>): Map<String, PsiElement> {
    if (queryIds.isEmpty()) {
      return emptyMap()
    }

    // Early return if in dumb mode
    if (DumbService.isDumb(project)) {
      log.debug("Project in dumb mode, returning empty results for XML tag lookup")
      return emptyMap()
    }

    val startTime = System.nanoTime()
    val result = ConcurrentHashMap<String, PsiElement>()
    val uncachedIds = mutableSetOf<String>()

    // Phase 1: Check cache for existing entries (safe - no index access)
    queryIds.forEach { queryId ->
      xmlTagCache[queryId]?.get()?.let { cachedElement ->
        if (cachedElement.isValid) {
          result[queryId] = cachedElement
          cacheHits++
        } else {
          xmlTagCache.remove(queryId)
          uncachedIds.add(queryId)
        }
      }
        ?: run {
          uncachedIds.add(queryId)
          cacheMisses++
        }
    }

    if (uncachedIds.isEmpty()) {
      logPerformance("findMultipleXmlTagsById (cache-only)", startTime, queryIds.size)
      return result
    }

    // Phase 2: Safe index access for uncached IDs
    val batchResults = safeIndexAccess { performBatchXmlTagLookup(uncachedIds) } ?: emptyMap()

    result.putAll(batchResults)
    updateXmlTagCache(batchResults)

    logPerformance("findMultipleXmlTagsById", startTime, queryIds.size)
    return result
  }

  private fun updateXmlTagCache(newResults: Map<String, PsiElement>) {
    // Implement cache size management
    if (xmlTagCache.size + newResults.size > MAX_CACHE_SIZE) {
      cleanupCache(xmlTagCache as ConcurrentHashMap<String, WeakReference<*>>)
    }

    newResults.forEach { (queryId, element) -> xmlTagCache[queryId] = WeakReference(element) }
  }

  /** Safe batch XML lookup with proper error handling */
  private fun performBatchXmlTagLookup(queryIds: Set<String>): Map<String, PsiElement> {
    val result = mutableMapOf<String, PsiElement>()

    try {
      val fileIndex = FileBasedIndex.getInstance()
      val processedFiles = mutableSetOf<String>()

      // Get all files that might contain our query IDs
      val candidateFiles = mutableSetOf<String>()
      queryIds.forEach { queryId ->
        try {
          val filePaths =
            fileIndex.getValues(
              SQLQueryFileIndexer.KEY,
              queryId,
              GlobalSearchScope.projectScope(project),
            )
          candidateFiles.addAll(filePaths)
        } catch (e: IndexNotReadyException) {
          log.debug("Index not ready for queryId: $queryId")
          // Continue with other queryIds
        }
      }
      // Process each file only once
      candidateFiles.forEach { filePath ->
        if (processedFiles.add(filePath)) {
          processXmlFileForBatch(filePath, queryIds, result)
        }
      }
    } catch (e: Exception) {
      log.warn("Error in batch XML tag lookup", e)
    }

    return result
  }

  private fun processXmlFileForBatch(
    filePath: String,
    targetIds: Set<String>,
    result: MutableMap<String, PsiElement>,
  ) {
    try {
      val virtualFile =
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath) ?: return

      val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile ?: return

      val rootTag = psiFile.rootTag ?: return
      if (rootTag.name != "Queries") return

      // Single pass through all query tags
      rootTag.findSubTags("query").forEach { queryTag ->
        val queryId = queryTag.getAttributeValue("id")
        if (queryId != null && queryId in targetIds) {
          result[queryId] = queryTag
        }
      }
    } catch (e: Exception) {
      log.debug("Error processing XML file $filePath", e)
    }
  }

  /** Similar updates for interface lookup */
  fun findMultipleInterfacesById(queryIds: Set<String>): Map<String, PsiElement> {
    if (queryIds.isEmpty() || DumbService.isDumb(project)) {
      return emptyMap()
    }

    val startTime = System.nanoTime()
    val result = ConcurrentHashMap<String, PsiElement>()
    val uncachedIds = mutableSetOf<String>()

    // Phase 1: Check cache
    queryIds.forEach { queryId ->
      interfaceCache[queryId]?.get()?.let { cachedElement ->
        if (cachedElement.isValid) {
          result[queryId] = cachedElement
          cacheHits++
        } else {
          interfaceCache.remove(queryId)
          uncachedIds.add(queryId)
        }
      }
        ?: run {
          uncachedIds.add(queryId)
          cacheMisses++
        }
    }

    if (uncachedIds.isEmpty()) {
      logPerformance("findMultipleInterfacesById (cache-only)", startTime, queryIds.size)
      return result
    }

    // Phase 2: Safe index access
    val batchResults = safeIndexAccess { performBatchInterfaceLookup(uncachedIds) } ?: emptyMap()

    result.putAll(batchResults)
    updateInterfaceCache(batchResults)

    logPerformance("findMultipleInterfacesById", startTime, queryIds.size)
    return result
  }

  private fun processJavaFileForSQLRefAnnotations(
    filePath: String,
    targetIds: Set<String>,
  ): Map<String, PsiElement> {
    val result = mutableMapOf<String, PsiElement>()

    try {
      val virtualFile =
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
          ?: return emptyMap()

      val psiFile =
        PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: return emptyMap()

      // Get settings once for this file
      val settings = project.getQueryRefSettings()
      val annotationFqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }
      val attributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }

      // Single pass through the file looking for SQLRef annotations
      psiFile.accept(
        object : JavaRecursiveElementVisitor() {
          override fun visitAnnotation(annotation: PsiAnnotation) {
            super.visitAnnotation(annotation)

            // Check if this is an SQLRef annotation
            if (annotation.qualifiedName == annotationFqn) {
              val refIdValue = annotation.findAttributeValue(attributeName)?.text?.replace("\"", "")

              if (refIdValue != null && refIdValue in targetIds) {
                // Found a matching SQLRef annotation
                // The target should be the interface/class containing this annotation
                val containingClass = annotation.asKtClassOrObject()
                if (containingClass != null) {
                  result[refIdValue] = containingClass
                  log.debug(
                    "Found SQLRef annotation for queryId '$refIdValue' in class '${containingClass.name}'"
                  )
                }
              }
            }
          }
        }
      )
    } catch (e: Exception) {
      log.debug("Error processing Java file $filePath for SQLRef annotations", e)
    }

    return result
  }

  private fun updateInterfaceCache(newResults: Map<String, PsiElement>) {
    if (interfaceCache.size + newResults.size > QueryIndexService2.MAX_CACHE_SIZE) {
      cleanupCache(interfaceCache as ConcurrentHashMap<String, WeakReference<*>>)
    }
    newResults.forEach { (queryId, element) -> interfaceCache[queryId] = WeakReference(element) }
  }

  private fun cleanupCache(cache: ConcurrentHashMap<String, WeakReference<*>>) {
    val iterator = cache.entries.iterator()
    var removed = 0

    while (iterator.hasNext() && removed < MAX_CACHE_SIZE / 4) {
      val entry = iterator.next()
      if (entry.value.get() == null) {
        iterator.remove()
        removed++
      }
    }

    log.debug("Cleaned up $removed stale cache entries")
  }

  private fun performBatchInterfaceLookup(queryIds: Set<String>): Map<String, PsiElement> {
    val result = mutableMapOf<String, PsiElement>()

    try {
      val fileIndex = FileBasedIndex.getInstance()
      val queryIdToFiles = mutableMapOf<String, MutableSet<String>>()

      queryIds.forEach { queryId ->
        try {
          val filePaths =
            fileIndex.getValues(
              SQLRefAnnotationIndex.KEY,
              queryId,
              GlobalSearchScope.projectScope(project),
            )
          if (filePaths.isNotEmpty()) {
            queryIdToFiles[queryId] = filePaths.toMutableSet()
          }
        } catch (e: IndexNotReadyException) {
          log.debug("Index not ready for SQLRef queryId: $queryId")
        }
      }

      // Group by file path and process
      val fileToQueryIds = mutableMapOf<String, MutableSet<String>>()
      queryIdToFiles.forEach { (queryId, filePaths) ->
        filePaths.forEach { filePath ->
          fileToQueryIds.computeIfAbsent(filePath) { mutableSetOf() }.add(queryId)
        }
      }

      fileToQueryIds.forEach { (filePath, targetQueryIds) ->
        val fileResults = processJavaFileForSQLRefAnnotations(filePath, targetQueryIds)
        result.putAll(fileResults)
      }
    } catch (e: Exception) {
      log.warn("Error in batch interface lookup", e)
    }

    return result
  }

  private fun logPerformance(operation: String, startTime: Long, itemCount: Int) {
    val duration = (System.nanoTime() - startTime) / 1_000_000
    if (duration > 50) { // Log operations taking >50ms
      log.info(
        "$operation: ${duration}ms for $itemCount items (${duration.toDouble() / itemCount}ms per item)"
      )
    }

    // Log cache statistics periodically
    val totalRequests = cacheHits + cacheMisses
    if (totalRequests > 0 && totalRequests % 100 == 0) {
      val hitRate = (cacheHits.toDouble() / totalRequests * 100).toInt()
      log.info("Cache hit rate: $hitRate% ($cacheHits hits, $cacheMisses misses)")
    }
  }
}
