// src/main/kotlin/com/github/edxref/query/index/SQLQueryFileIndexer.kt
package com.github.edxref.query.index

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.xml.XmlFile
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class SQLQueryFileIndexer : FileBasedIndexExtension<String, String>() {

	// Companion object for the index key
	companion object {
		val KEY: ID<String, String> =
			ID.create("com.edxref.sqlquery.index") // Ensure this matches usage
		private val log: Logger = logger<SQLQueryFileIndexer>() // Logger instance
		private val QUERY_FILE_PATTERN = Regex(".*-queries\\.xml$", RegexOption.IGNORE_CASE)
		private val QUERIES_DIR_NAME = "queries"
		private val RESOURCES_DIR_NAME = "resources"
	}


	// Define the index key
	override fun getName(): ID<String, String> = KEY

	// Define how keys are described/serialized
	override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

	// Define how values (file paths) are described/serialized
	override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

	// Increment this version if you change the indexing logic
	override fun getVersion(): Int =
		2 // Increment if logic changed (e.g., adding logging isn't a logic change, but good practice)

	override fun getInputFilter(): FileBasedIndex.InputFilter {
		return FileBasedIndex.InputFilter { file: VirtualFile ->
			// Fast path: check extension first
			if (!file.extension.equals("xml", ignoreCase = true)) return@InputFilter false

			// Use compiled pattern
			if (!QUERY_FILE_PATTERN.matches(file.name)) return@InputFilter false

			// Cache parent lookups
			val parent = file.parent ?: return@InputFilter false
			if (parent.name != QUERIES_DIR_NAME) return@InputFilter false

			val grandParent = parent.parent ?: return@InputFilter false
			grandParent.name == RESOURCES_DIR_NAME
		}
	}

	// Determines if external changes require re-indexing (usually true)
	override fun dependsOnFileContent(): Boolean = true

	// The core indexing logic
	override fun getIndexer(): DataIndexer<String, String, FileContent> {
		return DataIndexer { inputData ->
			val file = inputData.file
			log.info("Indexer running for file: ${file.path}") // Log entry for file
			val map = mutableMapOf<String, String>()

			try {
				// Use pre-provided PSI - more efficient and safer in indexer context
				val psiFile = inputData.psiFile

				if (psiFile is XmlFile) {
					if (log.isDebugEnabled) {
						log.debug("File '${file.name}' is XmlFile.")
					}
					val root = psiFile.rootTag
					if (root?.name == "Queries") {
						if (log.isDebugEnabled) {
							log.debug("Found root tag '<Queries>' in '${file.name}'.")
						}
						root.findSubTags("query").forEach { tag ->
							val queryId = tag.getAttributeValue("id")
							if (queryId != null && queryId.isNotBlank()) {
								log.debug("Found query id='$queryId' in file '${file.name}'. Mapping to path.")
								map[queryId] = file.path // Map ID to file path
							} else {
								log.warn("Found <query> tag without valid 'id' attribute in file '${file.name}'.")
							}
						}
					} else if (log.isDebugEnabled) {
						log.debug("Root tag is not '<Queries>' (found '${root?.name}') in file '${file.name}'. Skipping tags.")
					}
				} else if (log.isDebugEnabled) {
					log.debug("File '${file.name}' is not an XmlFile (Type: ${psiFile.javaClass.name}). Skipping.")
				}
			} catch (e: Exception) {
				log.error("Exception during indexing file '${file.path}'.", e)
				return@DataIndexer emptyMap()
			}

			log.info("Indexer finished for file: ${file.path}. Found ${map.size} query IDs.")
			map
		}
	}
}
