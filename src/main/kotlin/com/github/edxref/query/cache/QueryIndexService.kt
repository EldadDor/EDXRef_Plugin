// src/main/kotlin/com/github/edxref/query/cache/QueryIndexService.kt
package com.github.edxref.query.cache

// Keep existing imports...
import com.github.edxref.query.index.SQLQueryFileIndexer
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
// Remove WSConsumerSettings import if only used for logIfEnabled
// import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService // <<< ADDED IMPORT
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.indexing.FileBasedIndex

@Service(Service.Level.PROJECT)
class QueryIndexService(private val project: Project) {

    // Use standard IntelliJ logger
    private val log: Logger = logger<QueryIndexService>() // Use direct initialization

    // Removed logIfEnabled helper - rely on standard log configuration

    private fun getSettings(project: Project) = project.getQueryRefSettings()
    private fun getSqlRefAnnotationFqn(project: Project) = getSettings(project).sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }
    private fun getSqlRefAnnotationAttributeName(project: Project) = getSettings(project).sqlRefAnnotationAttributeName.ifBlank { "refId" }

    companion object {
        fun getInstance(project: Project): QueryIndexService = project.getService(QueryIndexService::class.java)
    }

    // Cache: Query ID -> SmartPointer<PsiClass>
    private val interfaceCache: CachedValue<Map<String, SmartPsiElementPointer<PsiClass>>> = CachedValuesManager.getManager(project).createCachedValue {
        log.info("Building/Rebuilding interface cache...") // Log cache build start
        val resultMap = mutableMapOf<String, SmartPsiElementPointer<PsiClass>>()
        val psiFacade = JavaPsiFacade.getInstance(project)
        val annotationFqn = getSqlRefAnnotationFqn(project)
        val attributeName = getSqlRefAnnotationAttributeName(project)
        val annotationClass = psiFacade.findClass(annotationFqn, GlobalSearchScope.allScope(project))

        if (annotationClass != null) {
            log.debug("Found annotation class: $annotationFqn")
            val candidates = AnnotatedElementsSearch.searchPsiClasses(annotationClass, GlobalSearchScope.projectScope(project)).findAll()
            log.debug("Found ${candidates.size} potential candidates annotated with $annotationFqn.")
            val pointerManager = SmartPointerManager.getInstance(project)
            candidates.forEach { psiClass ->
                psiClass.annotations.firstOrNull { ann -> ann.qualifiedName == annotationFqn }?.let { ann ->
                    ann.findAttributeValue(attributeName)?.text?.replace("\"", "")?.let { queryId ->
                        log.debug("Caching interface '${psiClass.name}' for queryId '$queryId'") // Log item add
                        resultMap[queryId] = pointerManager.createSmartPsiElementPointer(psiClass)
                    }
                }
            }
        } else {
            log.warn("Annotation class '$annotationFqn' not found. Interface cache might be incomplete.") // Log warning if class not found
        }
        log.info("Interface cache build complete. Found ${resultMap.size} entries.") // Log cache build end
        CachedValueProvider.Result.create(resultMap, PsiModificationTracker.MODIFICATION_COUNT)
    }

    // Cache: Query ID -> SmartPointer<XmlTag>
    private val xmlTagCache: CachedValue<Map<String, SmartPsiElementPointer<XmlTag>>> = CachedValuesManager.getManager(project).createCachedValue {
        log.info("Building/Rebuilding XML tag cache...") // Log cache build start
        val resultMap = mutableMapOf<String, SmartPsiElementPointer<XmlTag>>()
        val dumbService = DumbService.getInstance(project) // <<< GET DUMB SERVICE INSTANCE

        // *** ADD DUMB MODE CHECK HERE ***
        if (dumbService.isDumb) {
            log.warn("XML tag cache computation skipped: Project is in dumb mode.")
            // Return empty map and depend on DumbService to recompute when indexing finishes
            return@createCachedValue CachedValueProvider.Result.create(emptyMap(), dumbService)
        }
        // *** END DUMB MODE CHECK ***

        // Proceed only if not in dumb mode
        val index = FileBasedIndex.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val pointerManager = SmartPointerManager.getInstance(project)
        val searchScope = GlobalSearchScope.projectScope(project)

        try { // Wrap index access in try-catch just in case
            val allKeys = index.getAllKeys(SQLQueryFileIndexer.KEY, project) // <<< Access index only if not dumb
            log.debug("Found ${allKeys.size} query IDs in the index.")

            for (queryId in allKeys) {
                log.debug("Processing queryId '$queryId' for XML tag cache.")
                val filePaths = index.getValues(SQLQueryFileIndexer.KEY, queryId, searchScope)

                val tagPointer: SmartPsiElementPointer<XmlTag>? = filePaths.firstNotNullOfOrNull { filePath ->
                    val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                    if (vFile == null || !searchScope.contains(vFile)) {
                        log.debug("File path '$filePath' for queryId '$queryId' not found or out of scope. Skipping.") // Log skip
                        return@firstNotNullOfOrNull null
                    }

                    val psiFile = psiManager.findFile(vFile)
                    if (psiFile is XmlFile) {
                        psiFile.rootTag?.findSubTags("query")?.firstOrNull { tag ->
                            tag.getAttributeValue("id") == queryId
                        }?.let { foundTag ->
                            log.debug("Found XML tag for queryId '$queryId' in file '${vFile.path}'. Caching pointer.") // Log item add
                            pointerManager.createSmartPsiElementPointer(foundTag)
                        }
                    } else {
                        log.warn("File '${vFile.path}' for queryId '$queryId' is not an XML file. Skipping.") // Log warning for non-XML
                        null
                    }
                }

                tagPointer?.let { resultMap[queryId] = it }
            }
        } catch (e: IllegalStateException) {
            // Log the exception if it still somehow occurs despite the dumb check
            log.error("IllegalStateException during index access in xmlTagCache computation, even after dumb check.", e)
            // Return empty map to avoid further issues in this computation cycle
            return@createCachedValue CachedValueProvider.Result.create(emptyMap(), dumbService) // Depend on dumb service to retry
        }

        log.info("XML tag cache build complete. Found ${resultMap.size} entries.") // Log cache build end
        // Depend ONLY on PSI modifications when not in dumb mode.
        CachedValueProvider.Result.create(resultMap, PsiModificationTracker.MODIFICATION_COUNT)
    }


    fun findInterfaceById(queryId: String): PsiClass? {
        log.debug("Looking up interface for queryId '$queryId'") // Log lookup start
        val result = interfaceCache.value[queryId]?.element
        log.debug("Interface lookup for queryId '$queryId' result: ${if (result != null) "Found (${result.name})" else "Not Found"}") // Log lookup result
        return result
    }

    fun findXmlTagById(queryId: String): XmlTag? {
        log.debug("Looking up XML tag for queryId '$queryId'") // Log lookup start
        // This call might trigger the cache computation if needed
        val result = xmlTagCache.value[queryId]?.element
        log.debug("XML tag lookup for queryId '$queryId' result: ${if (result != null) "Found in file (${result.containingFile.virtualFile?.path})" else "Not Found"}") // Log lookup result
        return result
    }
}
