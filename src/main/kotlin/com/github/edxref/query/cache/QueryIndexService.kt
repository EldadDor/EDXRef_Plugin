package com.github.edxref.query.cache

import com.github.edxref.query.index.QueryUtilsUsageIndex
import com.github.edxref.query.index.SQLQueryFileIndexer
import com.github.edxref.query.index.SQLRefAnnotationIndex
import com.github.edxref.query.ng.index.NGQueryUtilsIndex
import com.github.edxref.query.ng.index.NGXmlQueryIndex
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool

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

  /** Safe index access with proper dumb mode handling */
  private fun <T> safeIndexAccess(operation: () -> T): T? {
    return try {
      if (DumbService.isDumb(project)) {
        log.debug("Skipping index access - project is in dumb mode")
        return null
      }

      // Use ReadAction for thread safety
      ReadAction.compute<T, Exception> { operation() }
    } catch (e: IndexNotReadyException) {
      log.debug("Index not ready, deferring operation: ${e.message}")
      null
    } catch (e: Exception) {
      log.warn("Error during index access", e)
      null
    }
  }

  /**
   * Batch operation to find multiple XML tags by their IDs Optimized for memory efficiency and
   * parallel processing
   */
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

    // Phase 2: Safe index access for uncached IDs
    val batchResults = safeIndexAccess { performBatchXmlTagLookup(uncachedIds) } ?: emptyMap()

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
    if (queryIds.isEmpty()) {
      return emptyMap()
    }

    // Early return if in dumb mode
    if (DumbService.isDumb(project)) {
      log.debug("Project in dumb mode, returning empty results for interface lookup")
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

  /** Single batch XML lookup - processes one chunk with proper error handling */
  private fun performSingleBatchXmlLookup(queryIds: Set<String>): Map<String, PsiElement> {
    val result = mutableMapOf<String, PsiElement>()
    val processedFiles = mutableSetOf<String>()

    try {
      val fileIndex = FileBasedIndex.getInstance()

      // Get all files that might contain our query IDs
      val candidateFiles = mutableSetOf<String>()
      queryIds.forEach { queryId ->
        try {
          fileIndex.processValues(
            SQLQueryFileIndexer.KEY,
            queryId,
            null,
            Processor { filePath: String ->
              candidateFiles.add(filePath)
              true
            }
              as FileBasedIndex.ValueProcessor<in String>,
            GlobalSearchScope.projectScope(project),
          )
        } catch (e: IndexNotReadyException) {
          log.debug("Index not ready for queryId: $queryId")
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

  /** Process a single XML file looking for multiple query IDs */
  private fun processXmlFileForBatch(
    filePath: String,
    targetIds: Set<String>,
    result: MutableMap<String, PsiElement>,
  ) {
    try {
      val virtualFile =
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath) ?: return

      val psiFile =
        ReadAction.compute<PsiFile?, Exception> {
          PsiManager.getInstance(project).findFile(virtualFile)
        } as? XmlFile ?: return

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

  /** Single batch interface lookup - FIXED to use SQLRef annotation index */
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

  /** Single batch interface lookup with proper error handling */
  private fun performSingleBatchInterfaceLookup(queryIds: Set<String>): Map<String, PsiElement> {
    val result = mutableMapOf<String, PsiElement>()

    try {
      val fileIndex = FileBasedIndex.getInstance()

      // Step 1: Use SQLRefAnnotationIndex to get file paths for all query IDs
      val queryIdToFiles = mutableMapOf<String, MutableSet<String>>()

      queryIds.forEach { queryId ->
        try {
          fileIndex.processValues(
            SQLRefAnnotationIndex.KEY,
            queryId,
            null,
            Processor { filePath: String ->
              queryIdToFiles.computeIfAbsent(queryId) { mutableSetOf() }.add(filePath)
              true
            }
              as FileBasedIndex.ValueProcessor<in String>,
            GlobalSearchScope.projectScope(project),
          )
        } catch (e: IndexNotReadyException) {
          log.debug("Index not ready for SQLRef queryId: $queryId")
        }
      }

      // Step 2: Group by file path to minimize file processing
      val fileToQueryIds = mutableMapOf<String, MutableSet<String>>()
      queryIdToFiles.forEach { (queryId, filePaths) ->
        filePaths.forEach { filePath ->
          fileToQueryIds.computeIfAbsent(filePath) { mutableSetOf() }.add(queryId)
        }
      }

      // Step 3: Process each file only once
      fileToQueryIds.forEach { (filePath, targetQueryIds) ->
        val fileResults = processJavaFileForSQLRefAnnotations(filePath, targetQueryIds)
        result.putAll(fileResults)
      }
    } catch (e: Exception) {
      log.warn("Error in batch SQLRef annotation lookup using index", e)
    }

    return result
  }

  /**
   * Process a single Java file looking for SQLRef annotations with specific refIds FIXED: Removed
   * incorrect asKtClassOrObject() usage
   */
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
        ReadAction.compute<PsiFile?, Exception> {
          PsiManager.getInstance(project).findFile(virtualFile)
        } as? PsiJavaFile ?: return emptyMap()

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
                val containingClass = PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java)
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

  /**
   * Batch operation to find QueryUtils usages by multiple query IDs Uses the QueryUtilsUsageIndex
   * for efficient file discovery
   */
  fun findMultipleQueryUtilsUsagesById(queryIds: Set<String>): Map<String, List<PsiElement>> {
    if (queryIds.isEmpty()) {
      return emptyMap()
    }

    // Early return if in dumb mode
    if (DumbService.isDumb(project)) {
      log.debug("Project in dumb mode, returning empty results for QueryUtils usage lookup")
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

    // Phase 2: Safe index access for uncached IDs
    val batchResults =
      safeIndexAccess { performBatchQueryUtilsUsageLookup(uncachedIds) } ?: emptyMap()

    result.putAll(batchResults)

    // Phase 3: Update cache with new results
    updateQueryUtilsUsageCache(batchResults)

    logPerformance("findMultipleQueryUtilsUsagesById", startTime, queryIds.size)
    return result
  }

  /** Core batch lookup logic - uses QueryUtilsUsageIndex efficiently */
  private fun performSingleBatchQueryUtilsLookup(
    queryIds: Set<String>
  ): Map<String, List<PsiElement>> {
    val result = mutableMapOf<String, List<PsiElement>>()

    try {
      val fileIndex = FileBasedIndex.getInstance()

      // Step 1: Use QueryUtilsUsageIndex to get file paths for all query IDs
      val queryIdToFiles = mutableMapOf<String, MutableSet<String>>()

      queryIds.forEach { queryId ->
        try {
          val processor =
            object : FileBasedIndex.ValueProcessor<String> {
              override fun process(file: VirtualFile, value: String): Boolean {
                queryIdToFiles.computeIfAbsent(queryId) { mutableSetOf() }.add(value)
                return true
              }
            }

          fileIndex.processValues(
            QueryUtilsUsageIndex.KEY,
            queryId,
            null,
            processor,
            GlobalSearchScope.projectScope(project),
          )
        } catch (e: IndexNotReadyException) {
          log.debug("Index not ready for QueryUtils queryId: $queryId")
        }
      }

      // Step 2: Group by file path to minimize file processing
      val fileToQueryIds = mutableMapOf<String, MutableSet<String>>()
      queryIdToFiles.forEach { (queryId, filePaths) ->
        filePaths.forEach { filePath ->
          fileToQueryIds.computeIfAbsent(filePath) { mutableSetOf() }.add(queryId)
        }
      }

      // Step 3: Process each file only once
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

  /** Batch QueryUtils usage lookup with proper chunking */
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

  /** Process a single Java file looking for multiple query ID usages */
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
        ReadAction.compute<PsiFile?, Exception> {
          PsiManager.getInstance(project).findFile(virtualFile)
        } as? PsiJavaFile ?: return emptyMap()

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
            if (args.isNotEmpty() && args[0] is PsiLiteralExpression) {
              val literal = args[0] as PsiLiteralExpression
              val queryId = literal.value as? String

              if (queryId != null && queryId in targetIds) {
                // Perform validation only for matching IDs
                if (isValidQueryUtilsCall(call, expectedMethodName, expectedFqn)) {
                  result.getOrPut(queryId) { mutableListOf() }.add(call)
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

  /** Validates that this is actually a QueryUtils call */
  private fun isValidQueryUtilsCall(
    call: PsiMethodCallExpression,
    expectedMethodName: String,
    expectedFqn: String,
  ): Boolean {
    val methodExpr = call.methodExpression

    // Method name check
    if (methodExpr.referenceName != expectedMethodName) return false

    // Type-based validation
    val qualifierExpr = methodExpr.qualifierExpression as? PsiReferenceExpression
    val qualifierType = qualifierExpr?.type
    val qualifierFqn = qualifierType?.canonicalText

    return qualifierFqn == expectedFqn
  }

  // Memory management methods remain the same...

  private fun updateXmlTagCache(newResults: Map<String, PsiElement>) {
    if (xmlTagCache.size + newResults.size > MAX_CACHE_SIZE) {
      cleanupCache(xmlTagCache as ConcurrentHashMap<String, WeakReference<PsiElement>>)
    }
    newResults.forEach { (queryId, element) -> xmlTagCache[queryId] = WeakReference(element) }
  }

  private fun updateInterfaceCache(newResults: Map<String, PsiElement>) {
    if (interfaceCache.size + newResults.size > MAX_CACHE_SIZE) {
      cleanupCache(interfaceCache as ConcurrentHashMap<String, WeakReference<PsiElement>>)
    }
    newResults.forEach { (queryId, element) -> interfaceCache[queryId] = WeakReference(element) }
  }

  private fun updateQueryUtilsUsageCache(newResults: Map<String, List<PsiElement>>) {
    if (queryUtilsUsageCache.size + newResults.size > MAX_CACHE_SIZE) {
      cleanupCache(queryUtilsUsageCache as ConcurrentHashMap<String, WeakReference<PsiElement>>)
    }
    newResults.forEach { (queryId, usages) ->
      queryUtilsUsageCache[queryId] = WeakReference(usages)
    }
  }

  private fun cleanupCache(cache: ConcurrentHashMap<String, WeakReference<PsiElement>>) {
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

  // Other methods remain the same...

  fun findXmlTagById(queryId: String): PsiElement? {
    return findMultipleXmlTagsById(setOf(queryId))[queryId]
  }

  fun findInterfaceById(queryId: String): PsiElement? {
    return findMultipleInterfacesById(setOf(queryId))[queryId]
  }

  fun findQueryUtilsUsagesById(queryId: String): List<PsiElement> {
    return findMultipleQueryUtilsUsagesById(setOf(queryId))[queryId] ?: emptyList()
  }

  fun clearCaches() {
    xmlTagCache.clear()
    interfaceCache.clear()
    queryUtilsUsageCache.clear()
    cacheHits = 0
    cacheMisses = 0
    log.info("All caches cleared")
  }

  fun getCacheStats(): Map<String, Any> {
    return mapOf(
      "xmlTagCacheSize" to xmlTagCache.size,
      "interfaceCacheSize" to interfaceCache.size,
      "queryUtilsUsageCacheSize" to queryUtilsUsageCache.size,
      "cacheHits" to cacheHits,
      "cacheMisses" to cacheMisses,
      "hitRate" to
        if (cacheHits + cacheMisses > 0)
          (cacheHits.toDouble() / (cacheHits + cacheMisses) * 100).toInt()
        else 0,
    )
  }

  fun forceReindex() {
    try {
      val fileBasedIndex = FileBasedIndex.getInstance()

      // Request reindex for all your custom indexes
      fileBasedIndex.requestRebuild(NGQueryUtilsIndex.KEY)
      fileBasedIndex.requestRebuild(NGXmlQueryIndex.KEY)
//      fileBasedIndex.requestRebuild(QueryUtilsUsageIndex.KEY)

      // Clear local caches since they might contain stale data
      clearCaches()

      log.info("Requested reindex for all QueryRef indexes")
    } catch (e: Exception) {
      log.warn("Error requesting reindex", e)
    }
  }

  private fun logPerformance(operation: String, startTime: Long, itemCount: Int) {
    val duration = (System.nanoTime() - startTime) / 1_000_000
    if (duration > 50) {
      log.info(
        "$operation: ${duration}ms for $itemCount items (${duration.toDouble() / itemCount}ms per item)"
      )
    }

    val totalRequests = cacheHits + cacheMisses
    if (totalRequests > 0 && totalRequests % 100 == 0) {
      val hitRate = (cacheHits.toDouble() / totalRequests * 100).toInt()
      log.info("Cache hit rate: $hitRate% ($cacheHits hits, $cacheMisses misses)")
    }
  }
}
