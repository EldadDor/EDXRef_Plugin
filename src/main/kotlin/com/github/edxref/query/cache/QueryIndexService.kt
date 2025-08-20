// src/main/kotlin/com/github/edxref/query/cache/QueryIndexService.kt
package com.github.edxref.query.cache

import com.github.edxref.query.index.QueryUtilsUsageIndex
import com.github.edxref.query.index.SQLQueryFileIndexer
import com.github.edxref.query.index.SQLRefAnnotationIndex
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.util.indexing.FileBasedIndex
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import org.jetbrains.kotlin.idea.testIntegration.framework.KotlinPsiBasedTestFramework.Companion.asKtClassOrObject

@Service(Service.Level.PROJECT)
class QueryIndexService(private val project: Project) {

  companion object {
    private val log = logger<QueryIndexService>()
    private const val MAX_CACHE_SIZE = 5000
    private const val BATCH_SIZE = 50
    private const val MAX_PARALLEL_TASKS = 4

    fun getInstance(project: Project): QueryIndexService =
      project.getService(QueryIndexService::class.java)
  }

  // Memory-efficient caches with weak references
  private val xmlTagCache = ConcurrentHashMap<String, WeakReference<PsiElement>>()
  private val interfaceCache = ConcurrentHashMap<String, WeakReference<PsiElement>>()
  private val queryUtilsUsageCache = ConcurrentHashMap<String, WeakReference<List<PsiElement>>>()

  // Cache hit/miss statistics for monitoring
  @Volatile private var cacheHits = 0

  @Volatile private var cacheMisses = 0

  /**
   * Batch operation to find multiple XML tags by their IDs Optimized for memory efficiency and
   * parallel processing
   */
  fun findMultipleXmlTagsById(queryIds: Set<String>): Map<String, PsiElement> {
    if (queryIds.isEmpty() || DumbService.isDumb(project)) {
      return emptyMap()
    }

    val startTime = System.nanoTime()
    val result = ConcurrentHashMap<String, PsiElement>()
    val uncachedIds = mutableSetOf<String>()

    // Phase 1: Check cache for existing entries
    queryIds.forEach { queryId ->
      xmlTagCache[queryId]?.get()?.let { cachedElement ->
        if (cachedElement.isValid) {
          result[queryId] = cachedElement
          cacheHits++
        } else {
          // Remove stale cache entry
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

    // Phase 2: Batch lookup for uncached IDs
    val batchResults = performBatchXmlTagLookup(uncachedIds)
    result.putAll(batchResults)

    // Phase 3: Update cache with new results (memory-conscious)
    updateXmlTagCache(batchResults)

    logPerformance("findMultipleXmlTagsById", startTime, queryIds.size)
    return result
  }

  /**
   * Batch operation to find multiple interfaces by their IDs Uses parallel processing for large
   * batches
   */
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

    // Phase 2: Batch lookup with parallel processing
    val batchResults = performBatchInterfaceLookup(uncachedIds)
    result.putAll(batchResults)

    // Phase 3: Update cache
    updateInterfaceCache(batchResults)

    logPerformance("findMultipleInterfacesById", startTime, queryIds.size)
    return result
  }

  /** Optimized batch XML tag lookup using chunked parallel processing */
  private fun performBatchXmlTagLookup(queryIds: Set<String>): Map<String, PsiElement> {
    if (queryIds.size <= BATCH_SIZE) {
      return performSingleBatchXmlLookup(queryIds)
    }

    // For large sets, use parallel processing with chunks
    val chunks = queryIds.chunked(BATCH_SIZE)
    val futures =
      chunks.map { chunk ->
        CompletableFuture.supplyAsync(
          { performSingleBatchXmlLookup(chunk.toSet()) },
          ForkJoinPool.commonPool(),
        )
      }

    // Combine results from all chunks
    val result = ConcurrentHashMap<String, PsiElement>()
    futures.forEach { future ->
      try {
        result.putAll(future.get())
      } catch (e: Exception) {
        log.warn("Error in parallel XML tag lookup", e)
      }
    }

    return result
  }

  /** Single batch XML lookup - processes one chunk */
  private fun performSingleBatchXmlLookup(queryIds: Set<String>): Map<String, PsiElement> {
    val result = mutableMapOf<String, PsiElement>()
    val fileIndex = FileBasedIndex.getInstance()
    val processedFiles = mutableSetOf<String>()

    try {
      // Get all files that might contain our query IDs
      val candidateFiles = mutableSetOf<String>()
      queryIds.forEach { queryId ->
        fileIndex
          .getValues(SQLQueryFileIndexer.KEY, queryId, GlobalSearchScope.projectScope(project))
          .forEach { filePath -> candidateFiles.add(filePath) }
      }

      // Process each file only once, looking for all needed IDs
      candidateFiles.forEach { filePath ->
        if (processedFiles.add(filePath)) { // Only process each file once
          processXmlFileForBatch(filePath, queryIds, result)
        }
      }
    } catch (e: Exception) {
      log.warn("Error in batch XML tag lookup", e)
    }

    return result
  }

  /** Process a single XML file looking for multiple query IDs */
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

  /** Optimized batch interface lookup */
  private fun performBatchInterfaceLookup(queryIds: Set<String>): Map<String, PsiElement> {
    if (queryIds.size <= BATCH_SIZE) {
      return performSingleBatchInterfaceLookup(queryIds)
    }

    // Parallel processing for large sets
    val chunks = queryIds.chunked(BATCH_SIZE)
    val futures =
      chunks.map { chunk ->
        CompletableFuture.supplyAsync(
          { performSingleBatchInterfaceLookup(chunk.toSet()) },
          ForkJoinPool.commonPool(),
        )
      }

    val result = ConcurrentHashMap<String, PsiElement>()
    futures.forEach { future ->
      try {
        result.putAll(future.get())
      } catch (e: Exception) {
        log.warn("Error in parallel interface lookup", e)
      }
    }

    return result
  }

  /** Single batch interface lookup - FIXED to use SQLRef annotation index */
  private fun performSingleBatchInterfaceLookup(queryIds: Set<String>): Map<String, PsiElement> {
    val result = mutableMapOf<String, PsiElement>()
    val fileIndex = FileBasedIndex.getInstance()

    try {
      // Step 1: Use SQLRefAnnotationIndex to get file paths for all query IDs
      val queryIdToFiles = mutableMapOf<String, MutableSet<String>>()

      queryIds.forEach { queryId ->
        // Use the SQLRef annotation index
        val filePaths =
          fileIndex.getValues(
            SQLRefAnnotationIndex.KEY,
            queryId,
            GlobalSearchScope.projectScope(project),
          )

        if (filePaths.isNotEmpty()) {
          queryIdToFiles[queryId] = filePaths.toMutableSet()
        }
      }

      // Step 2: Group by file path to minimize file processing
      val fileToQueryIds = mutableMapOf<String, MutableSet<String>>()
      queryIdToFiles.forEach { (queryId, filePaths) ->
        filePaths.forEach { filePath ->
          fileToQueryIds.computeIfAbsent(filePath) { mutableSetOf() }.add(queryId)
        }
      }

      // Step 3: Process each file only once, looking for all relevant query IDs
      fileToQueryIds.forEach { (filePath, targetQueryIds) ->
        val fileResults = processJavaFileForSQLRefAnnotations(filePath, targetQueryIds)
        result.putAll(fileResults)
      }
    } catch (e: Exception) {
      log.warn("Error in batch SQLRef annotation lookup using index", e)
    }

    return result
  }

  /** Process a single Java file looking for SQLRef annotations with specific refIds */
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

  /** Memory-conscious cache update for XML tags */
  private fun updateXmlTagCache(newResults: Map<String, PsiElement>) {
    // Implement cache size management
    if (xmlTagCache.size + newResults.size > MAX_CACHE_SIZE) {
      cleanupCache(xmlTagCache as ConcurrentHashMap<String, WeakReference<*>>)
    }

    newResults.forEach { (queryId, element) -> xmlTagCache[queryId] = WeakReference(element) }
  }

  /** Memory-conscious cache update for interfaces */
  private fun updateInterfaceCache(newResults: Map<String, PsiElement>) {
    if (interfaceCache.size + newResults.size > MAX_CACHE_SIZE) {
      cleanupCache(interfaceCache as ConcurrentHashMap<String, WeakReference<*>>)
    }
    newResults.forEach { (queryId, element) -> interfaceCache[queryId] = WeakReference(element) }
  }

  /** Clean up stale cache entries */
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

  /** Performance logging */
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

  // Existing single-item methods (optimized to use batch operations internally)
  fun findXmlTagById(queryId: String): PsiElement? {
    return findMultipleXmlTagsById(setOf(queryId))[queryId]
  }

  fun findInterfaceById(queryId: String): PsiElement? {
    return findMultipleInterfacesById(setOf(queryId))[queryId]
  }

  /** Clear all caches - useful for testing or memory pressure */
  fun clearCaches() {
    xmlTagCache.clear()
    interfaceCache.clear()
    queryUtilsUsageCache.clear()
    cacheHits = 0
    cacheMisses = 0
    log.info("All caches cleared")
  }

  /** Get cache statistics for monitoring */
  fun getCacheStats(): Map<String, Any> {
    return mapOf(
      "xmlTagCacheSize" to xmlTagCache.size,
      "interfaceCacheSize" to interfaceCache.size,
      "cacheHits" to cacheHits,
      "cacheMisses" to cacheMisses,
      "hitRate" to
        if (cacheHits + cacheMisses > 0)
          (cacheHits.toDouble() / (cacheHits + cacheMisses) * 100).toInt()
        else 0,
    )
  }

  /**
   * Batch operation to find QueryUtils usages by multiple query IDs Uses the QueryUtilsUsageIndex
   * for efficient file discovery
   */
  fun findMultipleQueryUtilsUsagesById(queryIds: Set<String>): Map<String, List<PsiElement>> {
    if (queryIds.isEmpty() || DumbService.isDumb(project)) {
      return emptyMap()
    }

    val startTime = System.nanoTime()
    val result = ConcurrentHashMap<String, List<PsiElement>>()
    val uncachedIds = mutableSetOf<String>()

    // Phase 1: Check cache for existing entries
    queryIds.forEach { queryId ->
      queryUtilsUsageCache[queryId]?.get()?.let { cachedUsages ->
        if (cachedUsages.all { it.isValid }) {
          result[queryId] = cachedUsages
          cacheHits++
        } else {
          // Remove stale cache entry
          queryUtilsUsageCache.remove(queryId)
          uncachedIds.add(queryId)
        }
      }
        ?: run {
          uncachedIds.add(queryId)
          cacheMisses++
        }
    }

    if (uncachedIds.isEmpty()) {
      logPerformance("findMultipleQueryUtilsUsagesById (cache-only)", startTime, queryIds.size)
      return result
    }

    // Phase 2: Batch lookup for uncached IDs using the index
    val batchResults = performBatchQueryUtilsUsageLookup(uncachedIds)
    result.putAll(batchResults)

    // Phase 3: Update cache with new results
    updateQueryUtilsUsageCache(batchResults)

    logPerformance("findMultipleQueryUtilsUsagesById", startTime, queryIds.size)
    return result
  }

  /** Single query ID lookup - uses batch operations internally for efficiency */
  fun findQueryUtilsUsagesById(queryId: String): List<PsiElement> {
    return findMultipleQueryUtilsUsagesById(setOf(queryId))[queryId] ?: emptyList()
  }

  /**
   * Batch QueryUtils usage lookup using your existing QueryUtilsUsageIndex This is where the magic
   * happens - we use your index efficiently!
   */
  private fun performBatchQueryUtilsUsageLookup(
    queryIds: Set<String>
  ): Map<String, List<PsiElement>> {
    if (queryIds.size <= BATCH_SIZE) {
      return performSingleBatchQueryUtilsLookup(queryIds)
    }

    // For large sets, use parallel processing with chunks
    val chunks = queryIds.chunked(BATCH_SIZE)
    val futures =
      chunks.map { chunk ->
        CompletableFuture.supplyAsync(
          { performSingleBatchQueryUtilsLookup(chunk.toSet()) },
          ForkJoinPool.commonPool(),
        )
      }

    // Combine results from all chunks
    val result = ConcurrentHashMap<String, List<PsiElement>>()
    futures.forEach { future ->
      try {
        val chunkResult = future.get()
        chunkResult.forEach { (queryId, usages) ->
          result.merge(queryId, usages) { existing, new -> existing + new }
        }
      } catch (e: Exception) {
        log.warn("Error in parallel QueryUtils usage lookup", e)
      }
    }

    return result
  }

  /** Core batch lookup logic - uses your QueryUtilsUsageIndex efficiently */
  private fun performSingleBatchQueryUtilsLookup(
    queryIds: Set<String>
  ): Map<String, List<PsiElement>> {
    val result = mutableMapOf<String, List<PsiElement>>()
    val fileIndex = FileBasedIndex.getInstance()

    try {
      // Step 1: Use your QueryUtilsUsageIndex to get file paths for all query IDs
      val queryIdToFiles = mutableMapOf<String, MutableSet<String>>()

      queryIds.forEach { queryId ->
        // This uses your existing index: QueryUtilsUsageIndex.KEY
        val filePaths =
          fileIndex.getValues(
            QueryUtilsUsageIndex.KEY,
            queryId,
            GlobalSearchScope.projectScope(project),
          )

        if (filePaths.isNotEmpty()) {
          queryIdToFiles[queryId] = filePaths.toMutableSet()
        }
      }

      // Step 2: Group by file path to minimize file processing
      val fileToQueryIds = mutableMapOf<String, MutableSet<String>>()
      queryIdToFiles.forEach { (queryId, filePaths) ->
        filePaths.forEach { filePath ->
          fileToQueryIds.computeIfAbsent(filePath) { mutableSetOf() }.add(queryId)
        }
      }

      // Step 3: Process each file only once, looking for all relevant query IDs
      fileToQueryIds.forEach { (filePath, targetQueryIds) ->
        val fileUsages = processJavaFileForBatchUsages(filePath, targetQueryIds)
        fileUsages.forEach { (queryId, usages) ->
          result.merge(queryId, usages) { existing, new -> existing + new }
        }
      }
    } catch (e: Exception) {
      log.warn("Error in batch QueryUtils usage lookup using index", e)
    }

    return result
  }

  /**
   * Process a single Java file looking for multiple query ID usages This validates the index
   * results and extracts actual PsiElements
   */
  private fun processJavaFileForBatchUsages(
    filePath: String,
    targetIds: Set<String>,
  ): Map<String, List<PsiElement>> {
    val result = mutableMapOf<String, MutableList<PsiElement>>()

    try {
      val virtualFile =
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
          ?: return emptyMap()

      val psiFile =
        PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: return emptyMap()

      // Get settings once for this file
      val settings = project.getQueryRefSettings()
      val expectedMethodName = settings.queryUtilsMethodName.ifBlank { "getQuery" }
      val expectedFqn = settings.queryUtilsFqn.ifBlank { "com.example.QueryUtils" }

      // Single pass through the file using optimized visitor
      psiFile.accept(
        object : JavaRecursiveElementVisitor() {
          override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
            super.visitMethodCallExpression(call)

            val methodExpr = call.methodExpression

            // Fast method name check
            if (methodExpr.referenceName != expectedMethodName) {
              return
            }

            // Extract query ID from first argument
            val args = call.argumentList.expressions
            if (args.size >= 1 && args[0] is PsiLiteralExpression) {
              val literal = args[0] as PsiLiteralExpression
              val queryId = literal.value as? String

              if (queryId != null && queryId in targetIds) {
                // Perform validation only for matching IDs
                if (isValidQueryUtilsCall(call, expectedMethodName, expectedFqn)) {
                  result.computeIfAbsent(queryId) { mutableListOf() }.add(call)
                }
              }
            }
          }
        }
      )
    } catch (e: Exception) {
      log.debug("Error processing Java file $filePath for QueryUtils usages", e)
    }

    // Convert MutableList to List for return type
    return result.mapValues { it.value.toList() }
  }

  /** Validates that this is actually a QueryUtils call (not just indexed heuristically) */
  private fun isValidQueryUtilsCall(
    call: PsiMethodCallExpression,
    expectedMethodName: String,
    expectedFqn: String,
  ): Boolean {
    val methodExpr = call.methodExpression

    // Method name check (already done, but keeping for completeness)
    if (methodExpr.referenceName != expectedMethodName) return false

    // Type-based validation - this is the expensive but accurate check
    val qualifierExpr = methodExpr.qualifierExpression as? PsiReferenceExpression
    val qualifierType = qualifierExpr?.type
    val qualifierFqn = qualifierType?.canonicalText

    return qualifierFqn == expectedFqn
  }

  // ... (rest of the existing methods for XML tags and interfaces remain the same)

  /** Memory-conscious cache update for QueryUtils usages */
  private fun updateQueryUtilsUsageCache(newResults: Map<String, List<PsiElement>>) {
    if (queryUtilsUsageCache.size + newResults.size > MAX_CACHE_SIZE) {
      cleanupCache(queryUtilsUsageCache as ConcurrentHashMap<String, WeakReference<*>>)
    }

    newResults.forEach { (queryId, usages) ->
      queryUtilsUsageCache[queryId] = WeakReference(usages)
    }
  }
}
