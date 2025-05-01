// src/main/kotlin/com/github/edxref/query/index/SQLQueryFileIndexer.kt
package com.github.edxref.query.index

import com.github.edxref.query.settings.QueryRefSettings
import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class SQLQueryFileIndexer : FileBasedIndexExtension<String, String>() {

    // Companion object for the index key
    companion object {
        val KEY: ID<String, String> = ID.create("com.edxref.sqlquery.index") // Ensure this matches usage
        private val log: Logger = logger<SQLQueryFileIndexer>() // Logger instance
    }

    // Define the index key
    override fun getName(): ID<String, String> = KEY

    // Define how keys are described/serialized
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    // Define how values (file paths) are described/serialized
    override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

    // Increment this version if you change the indexing logic
    override fun getVersion(): Int = 2 // Increment if logic changed (e.g., adding logging isn't a logic change, but good practice)

    // Filter: Only index specific XML files
    override fun getInputFilter(): FileBasedIndex.InputFilter {
        return FileBasedIndex.InputFilter { file: VirtualFile ->
            // 1. Check file type efficiently
            if (file.fileType != com.intellij.ide.highlighter.XmlFileType.INSTANCE) {
                // log.trace("InputFilter skipping non-XML: ${file.path}") // Use trace if needed, debug might be too verbose
                return@InputFilter false
            }

            // 2. Check file name efficiently (use nameSequence)
            if (!file.nameSequence.endsWith("-queries.xml", ignoreCase = true)) {
                // log.trace("InputFilter skipping wrong name: ${file.name}")
                return@InputFilter false
            }

            // 3. Check parent directory structure (heuristic)
            val parent = file.parent
            if (parent == null || !parent.nameSequence.contentEquals("queries")) {
                // log.trace("InputFilter skipping wrong parent dir name: ${parent?.name}")
                return@InputFilter false
            }

            // 4. Optional: Check grandparent directory name
            val grandParent = parent.parent
            if (grandParent == null || !grandParent.nameSequence.contentEquals("resources")) {
                // log.trace("InputFilter skipping wrong grandparent dir name: ${grandParent?.name}")
                // Decide if this check is too strict or desired based on your convention
                return@InputFilter false // Keep this line if grandparent MUST be 'resources'
            }

            // If all checks pass:
            log.debug("InputFilter ACCEPTED file: ${file.path}") // Log accepted files
            true // Index this file
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
                    // ... (rest of your existing indexer logic using psiFile) ...
                    log.debug("File '${file.name}' is XmlFile.")
                    val root = psiFile.rootTag
                    if (root?.name == "Queries") {
                        log.debug("Found root tag '<Queries>' in '${file.name}'.")
                        root.findSubTags("query").forEach { tag ->
                            val queryId = tag.getAttributeValue("id")
                            if (queryId != null && queryId.isNotBlank()) {
                                log.debug("Found query id='$queryId' in file '${file.name}'. Mapping to path.")
                                map[queryId] = file.path // Map ID to file path
                            } else {
                                log.warn("Found <query> tag without valid 'id' attribute in file '${file.name}'.")
                            }
                        }
                    } else {
                        log.debug("Root tag is not '<Queries>' (found '${root?.name}') in file '${file.name}'. Skipping tags.")
                    }
                } else {
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
