// src/main/kotlin/com/github/edxref/query/cache/QueryIndexService.kt
package com.github.edxref.query.cache

import com.github.edxref.query.index.SQLQueryFileIndexer
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
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

    private fun getSettings(project: Project) = project.getQueryRefSettings()
    private fun getSqlRefAnnotationFqn(project: Project) = getSettings(project).sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }
    private fun getSqlRefAnnotationAttributeName(project: Project) = getSettings(project).sqlRefAnnotationAttributeName.ifBlank { "refId" }

    companion object {
        fun getInstance(project: Project): QueryIndexService =
            project.getService(QueryIndexService::class.java)
    }

    // Cache: Query ID -> SmartPointer<PsiClass>
    private val interfaceCache: CachedValue<Map<String, SmartPsiElementPointer<PsiClass>>> =
        CachedValuesManager.getManager(project).createCachedValue {
            val resultMap = mutableMapOf<String, SmartPsiElementPointer<PsiClass>>()
            val psiFacade = JavaPsiFacade.getInstance(project)
            val annotationClass = psiFacade.findClass(getSqlRefAnnotationFqn(project), GlobalSearchScope.allScope(project))

            if (annotationClass != null) {
                val candidates = AnnotatedElementsSearch.searchPsiClasses(annotationClass, GlobalSearchScope.projectScope(project)).findAll()
                val pointerManager = SmartPointerManager.getInstance(project)
                candidates.forEach { psiClass ->
                    psiClass.annotations.firstOrNull { ann -> ann.qualifiedName == getSqlRefAnnotationFqn(project) }?.let { ann ->
                        ann.findAttributeValue(getSqlRefAnnotationAttributeName(project))?.text?.replace("\"", "")?.let { queryId ->
                            // Store a smart pointer to handle PSI changes
                            resultMap[queryId] = pointerManager.createSmartPsiElementPointer(psiClass)
                        }
                    }
                }
            }
            // Depend on project-wide PSI changes
            CachedValueProvider.Result.create(resultMap, PsiModificationTracker.MODIFICATION_COUNT)
        }

    // Cache: Query ID -> SmartPointer<XmlTag>
    private val xmlTagCache: CachedValue<Map<String, SmartPsiElementPointer<XmlTag>>> =
        CachedValuesManager.getManager(project).createCachedValue {
            val resultMap = mutableMapOf<String, SmartPsiElementPointer<XmlTag>>()
            val index = FileBasedIndex.getInstance()
            val psiManager = PsiManager.getInstance(project)
            val pointerManager = SmartPointerManager.getInstance(project)
            val searchScope = GlobalSearchScope.projectScope(project)
            val allKeys = index.getAllKeys(SQLQueryFileIndexer.KEY, project)

            for (queryId in allKeys) {
                val filePaths = index.getValues(SQLQueryFileIndexer.KEY, queryId, searchScope)

                // Use firstNotNullOfOrNull to find the first valid tag and create a pointer
                val tagPointer: SmartPsiElementPointer<XmlTag>? = filePaths.firstNotNullOfOrNull { filePath ->
                    val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                    if (vFile == null || !searchScope.contains(vFile)) return@firstNotNullOfOrNull null // Skip if file not found or out of scope

                    val psiFile = psiManager.findFile(vFile)
                    if (psiFile is XmlFile) {
                        psiFile.rootTag?.findSubTags("query")?.firstOrNull { tag ->
                            tag.getAttributeValue("id") == queryId
                        }?.let { foundTag ->
                            // Create pointer if tag found
                            pointerManager.createSmartPsiElementPointer(foundTag)
                        } // Returns SmartPsiElementPointer<XmlTag>?
                    } else {
                        null // Not an XML file
                    }
                } // End of firstNotNullOfOrNull lambda

                // If a pointer was created, add it to the map
                tagPointer?.let { resultMap[queryId] = it }
            }
            // Depend on project-wide PSI changes AND changes to the index itself
            CachedValueProvider.Result.create(resultMap, PsiModificationTracker.MODIFICATION_COUNT, FileBasedIndex.getInstance())
        }


    fun findInterfaceById(queryId: String): PsiClass? {
        // Retrieve from cache, dereference the smart pointer
        return interfaceCache.value[queryId]?.element
    }

    fun findXmlTagById(queryId: String): XmlTag? {
        // Retrieve from cache, dereference the smart pointer
        return xmlTagCache.value[queryId]?.element
    }
}
