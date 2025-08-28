package com.github.edxref.query.ng.index

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/** Optimized index for @SQLRef annotations Maps: refId value -> file path */
class NGSQLRefIndex : FileBasedIndexExtension<String, String>() {

  companion object {
    val KEY: ID<String, String> = ID.create("com.edxref.ng.sqlref.index")
    private val log = logger<NGSQLRefIndex>()
  }

  override fun getName(): ID<String, String> = KEY

  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

  override fun getVersion(): Int = 4 // Incremented for the fix

  override fun dependsOnFileContent(): Boolean = true

  override fun getInputFilter(): FileBasedIndex.InputFilter {
    return object :
      DefaultFileTypeSpecificInputFilter(com.intellij.ide.highlighter.JavaFileType.INSTANCE) {
      override fun acceptInput(file: VirtualFile): Boolean {
        // Additional filtering to improve performance
        return super.acceptInput(file) &&
          file.name.endsWith(".java") &&
          !file.path.contains("/test/") && // Skip test files for better performance
          !file.path.contains("/.gradle/") && // Skip gradle cache
          !file.path.contains("/.m2/") // Skip maven cache
      }
    }
  }

  override fun getIndexer(): DataIndexer<String, String, FileContent> {
    return DataIndexer { inputData ->
      val map = mutableMapOf<String, String>()

      try {
        // Quick text-based pre-check to avoid expensive PSI parsing
        val content = inputData.contentAsText.toString()
        if (!content.contains("SQLRef")) {
          return@DataIndexer emptyMap()
        }

        // Only parse PSI if the file likely contains SQLRef annotations
        val psiFile = inputData.psiFile as? PsiJavaFile ?: return@DataIndexer emptyMap()

        // Use a more efficient visitor that stops early
        psiFile.accept(OptimizedSQLRefVisitor(map, inputData.file.path))
      } catch (e: ProcessCanceledException) {
        // Always rethrow ProcessCanceledException
        throw e
      } catch (e: ReadAction.CannotReadException) {
        // Always rethrow ReadAction exceptions - never log them
        throw e
      } catch (e: Exception) {
        // Only log non-control-flow exceptions
        log.debug("Error indexing ${inputData.file.path}: ${e.message}")
      }

      map
    }
  }

  /** Optimized visitor that processes annotations efficiently */
  private class OptimizedSQLRefVisitor(
    private val resultMap: MutableMap<String, String>,
    private val filePath: String,
  ) : JavaRecursiveElementVisitor() {

    private var foundAnnotations = 0
    private val maxAnnotationsPerFile = 50 // Limit to prevent runaway processing

    override fun visitClass(aClass: PsiClass) {
      // Only visit class-level annotations, skip method/field level for performance
      aClass.modifierList?.let { modifierList -> visitModifierList(modifierList) }

      // Don't recurse into inner classes if we've found enough annotations
      if (foundAnnotations < maxAnnotationsPerFile) {
        super.visitClass(aClass)
      }
    }

    override fun visitMethod(method: PsiMethod) {
      // Visit method-level annotations
      method.modifierList?.let { modifierList -> visitModifierList(modifierList) }

      // Don't recurse into method body - annotations are only in modifier lists
    }

    override fun visitField(field: PsiField) {
      // Visit field-level annotations
      field.modifierList?.let { modifierList -> visitModifierList(modifierList) }
    }

    override fun visitAnnotation(annotation: PsiAnnotation) {
      try {
        // Quick text-based check first (no resolution needed)
        val annotationText = annotation.text
        if (annotationText.contains("SQLRef")) {

          // Extract refId without expensive resolution
          val refIdValue = extractRefIdValueFast(annotation)
          if (!refIdValue.isNullOrBlank()) {
            resultMap[refIdValue] = filePath
            foundAnnotations++

            if (log.isDebugEnabled) {
              log.debug("Indexed SQLRef: $refIdValue -> $filePath")
            }

            // Stop processing if we've found too many (prevents runaway processing)
            if (foundAnnotations >= maxAnnotationsPerFile) {
              return
            }
          }
        }
      } catch (e: ProcessCanceledException) {
        throw e
      } catch (e: ReadAction.CannotReadException) {
        throw e
      } catch (e: Exception) {
        // Ignore individual annotation processing errors
        if (log.isTraceEnabled) {
          log.trace("Error processing annotation: ${e.message}")
        }
      }

      // Don't call super.visitAnnotation() to avoid unnecessary recursion
    }

    /** Fast refId extraction without expensive PSI resolution */
    private fun extractRefIdValueFast(annotation: PsiAnnotation): String? {
      try {
        // Get the parameter list without triggering resolution
        val parameterList = annotation.parameterList
        val attributes = parameterList.attributes

        for (attribute in attributes) {
          val attrName = attribute.name

          // Check for refId attribute or default value
          if (attrName == "refId" || attrName == null) {
            val value = attribute.value
            if (value is PsiLiteralExpression) {
              val literalValue = value.value
              if (literalValue is String && literalValue.isNotBlank()) {
                return literalValue
              }
            }
          }
        }
      } catch (e: ProcessCanceledException) {
        throw e
      } catch (e: ReadAction.CannotReadException) {
        throw e
      } catch (e: Exception) {
        // Return null on any parsing error
      }

      return null
    }
  }
}
