/*
 * User: eadno1
 * Date: 20/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */
package com.github.edxref.query.index

import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class SQLRefAnnotationIndex : FileBasedIndexExtension<String, String>() {

  companion object {
    val KEY: ID<String, String> = ID.create("com.edxref.sqlref.annotation.index")
    private val log: Logger = logger<SQLRefAnnotationIndex>()
  }

  override fun getName(): ID<String, String> = KEY

  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

  override fun getVersion(): Int = 1

  override fun dependsOnFileContent(): Boolean = true

  override fun getInputFilter(): FileBasedIndex.InputFilter {
    // Index only Java files
    return DefaultFileTypeSpecificInputFilter(com.intellij.ide.highlighter.JavaFileType.INSTANCE)
  }

  override fun getIndexer(): DataIndexer<String, String, FileContent> {
    return DataIndexer { inputData ->
      val map = mutableMapOf<String, String>()
      try {
        val project = inputData.project
        // Check if project is in dumb mode to avoid IndexNotReadyException
        val (annotationFqn, attributeName) =
          if (DumbService.isDumb(project)) {
            // Use defaults during dumb mode
            "com.github.edxref.SQLRef" to "refId"
          } else {
            // Use settings when project is ready
            val settings = project.getQueryRefSettings()
            val fqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }
            val attr = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }
            fqn to attr
          }
        val content = inputData.contentAsText.toString()
        val simpleAnnotationName = annotationFqn.substringAfterLast('.')

        // Regex to find annotation with attribute
        val pattern = Regex("""@$simpleAnnotationName\s*\(\s*$attributeName\s*=\s*"([^"]+)"""")

        pattern.findAll(content).forEach { match ->
          val refIdValue = match.groupValues[1]
          if (refIdValue.isNotBlank()) {
            log.debug(
              "Indexer found SQLRef annotation: refId='$refIdValue' in ${inputData.file.name}"
            )
            map[refIdValue] = inputData.file.path
          }
        }
      } catch (e: Exception) {
        log.warn("Error indexing file ${inputData.file.name}", e)
      }
      map
    }
  }
}
