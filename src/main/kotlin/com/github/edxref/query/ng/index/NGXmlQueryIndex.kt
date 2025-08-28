package com.github.edxref.query.ng.index

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.xml.XmlFile
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/** Optimized index for XML query files Maps: queryId -> file path */
class NGXmlQueryIndex : FileBasedIndexExtension<String, String>() {

  companion object {
    val KEY: ID<String, String> = ID.create("com.edxref.ng.xml.query.index")
    private val log = logger<NGXmlQueryIndex>()
  }

  override fun getName(): ID<String, String> = KEY

  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

  override fun getVersion(): Int = 2

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
        // Quick text check first
        val content = inputData.contentAsText.toString()
        if (!content.contains("<query") || !content.contains("id=")) {
          return@DataIndexer emptyMap()
        }

        val psiFile = inputData.psiFile as? XmlFile ?: return@DataIndexer emptyMap()
        val rootTag = psiFile.rootTag

        if (rootTag?.name == "Queries") {
          val queryTags = rootTag.findSubTags("query")
          for (queryTag in queryTags) {
            val queryId = queryTag.getAttributeValue("id")
            if (!queryId.isNullOrBlank()) {
              map[queryId] = inputData.file.path
              if (log.isDebugEnabled) {
                log.debug("Indexed query: $queryId -> ${inputData.file.path}")
              }
            }
          }
        }
      } catch (e: ProcessCanceledException) {
        throw e
      } catch (e: ReadAction.CannotReadException) {
        throw e
      } catch (e: Exception) {
        log.debug("Error indexing ${inputData.file.path}: ${e.message}")
      }

      map
    }
  }
}
