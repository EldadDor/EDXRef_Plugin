package com.github.edxref.query.util

import com.github.edxref.query.cache.QueryIndexService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.xml.XmlTag

object QueryIdResolver {
  /*  fun resolveQueryInterface(queryId: String, project: Project): PsiClass? {
      // Find all classes annotated with @SQLRef
      val psiFacade = JavaPsiFacade.getInstance(project)
      val annotationClass = psiFacade.findClass("SQLRef", GlobalSearchScope.allScope(project))
          ?: return null

      val candidates = AnnotatedElementsSearch.searchPsiClasses(annotationClass, GlobalSearchScope.allScope(project)).findAll()
      return candidates.firstOrNull { psiClass ->
          psiClass.annotations.any { ann ->
              ann.qualifiedName == "SQLRef" &&
                      ann.findAttributeValue("refId")?.text?.replace("\"", "") == queryId
          }
      }
  }*/

  /* fun resolveQueryXml(queryId: String, project: Project): XmlTag? {
      val index = FileBasedIndex.getInstance()
      val filePaths = index.getValues(SQLQueryFileIndexer.KEY, queryId, GlobalSearchScope.allScope(project))
      for (filePath in filePaths) {
          val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
          val psiFile = vFile?.let { PsiManager.getInstance(project).findFile(it) }
          if (psiFile is XmlFile) {
              val root = psiFile.rootTag
              root?.findSubTags("query")?.firstOrNull { it.getAttributeValue("id") == queryId }?.let { return it }
          }
      }
      return null
  }*/
  fun resolveQueryInterface(queryId: String, project: Project): PsiClass? {
    // Delegate to the cached service
    return QueryIndexService.getInstance(project).findInterfaceById(queryId) as PsiClass?
  }

  fun resolveQueryXml(queryId: String, project: Project): XmlTag? {
    // Delegate to the cached service
    return QueryIndexService.getInstance(project).findXmlTagById(queryId) as XmlTag?
  }
}
