package com.github.edxref.index

// import
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings // Adjust
import com.intellij.psi.PsiJavaFile
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

/**
 * Simple index to quickly find files containing classes/interfaces annotated with @InternalApi.
 * Key: FQN of the @InternalApi annotation (retrieved from settings). Value: Boolean (true if found,
 * could be more complex like list of offsets).
 */
class InternalApiIndex : ScalarIndexExtension<String>() {

  companion object {
    // Unique ID for the index
    val INDEX_ID = ID.create<String, Void>("com.github.edxref.InternalApiIndex")
  }

  override fun getName(): ID<String, Void> = INDEX_ID

  // Index data based on file content
  override fun getIndexer(): DataIndexer<String, Void, FileContent> {
    return DataIndexer { inputData ->
      val project = inputData.project
      val internalApiFqn =
        project.getWSConsumerSettings().internalApiAnnotationFqn.ifBlank {
          return@DataIndexer emptyMap()
        }
      val file = inputData.psiFile

      var found = false
      // Simplified check - needs proper PSI traversal
      if (file is PsiJavaFile) {
        file.classes.forEach { psiClass ->
          if (psiClass.hasAnnotation(internalApiFqn)) {
            found = true
            return@forEach // Stop checking classes in this file
          }
          // Could also check methods here if needed
        }
      } else if (file is KtFile) {
        file.declarations.filterIsInstance<KtClass>().forEach { ktClass ->
          // Basic check - needs proper annotation resolution
          if (
            ktClass.annotationEntries.any {
              it.shortName?.asString() == internalApiFqn.substringAfterLast('.')
            }
          ) {
            found = true
            return@forEach
          }
          // Could also check methods here
        }
      }

      if (found) {
        mapOf(internalApiFqn to null) // Map key to null value for ScalarIndexExtension
      } else {
        emptyMap()
      }
    }
  }

  // How to read/write the key (FQN string)
  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  // Version of the index - bump this if indexing logic changes
  override fun getVersion(): Int = 1

  // Which files to index
  override fun getInputFilter(): FileBasedIndex.InputFilter {
    return DefaultFileTypeSpecificInputFilter(
      com.intellij.openapi.fileTypes.StdFileTypes.JAVA,
      KotlinFileType.INSTANCE,
    )
  }

  override fun dependsOnFileContent(): Boolean = true
}
