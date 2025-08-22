package com.github.edxref.query.ng.index

/*
 * User: eadno1
 * Date: 21/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.xml.XmlFile
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/** Simple index for XML query files Maps: queryId -> file path */
class NGXmlQueryIndex : FileBasedIndexExtension<String, String>() {

  companion object {
    val KEY: ID<String, String> = ID.create("com.edxref.ng.xml.query.index")
    private val log = logger<NGXmlQueryIndex>()
  }

  override fun getName(): ID<String, String> = KEY

  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

  override fun getVersion(): Int = 1

  override fun dependsOnFileContent(): Boolean = true

  override fun getInputFilter(): FileBasedIndex.InputFilter {
    return FileBasedIndex.InputFilter { file: VirtualFile ->
      file.name.endsWith("-queries.xml", ignoreCase = true) &&
        file.parent?.name == "queries" &&
        file.parent?.parent?.name == "resources"
    }
  }

  override fun getIndexer(): DataIndexer<String, String, FileContent> {
    return DataIndexer { inputData ->
      val map = mutableMapOf<String, String>()

      try {
        val psiFile = inputData.psiFile as? XmlFile ?: return@DataIndexer emptyMap()
        val rootTag = psiFile.rootTag

        if (rootTag?.name == "Queries") {
          rootTag.findSubTags("query").forEach { tag ->
            val queryId = tag.getAttributeValue("id")
            if (!queryId.isNullOrBlank()) {
              map[queryId] = inputData.file.path
              log.debug("Indexed query: $queryId -> ${inputData.file.path}")
            }
          }
        }
      } catch (e: Exception) {
        log.error("Error indexing ${inputData.file.path}", e)
      }

      map
    }
  }
}
