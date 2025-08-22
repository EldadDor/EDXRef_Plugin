package com.github.edxref.query.ng.index

import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/** Index for @SQLRef annotations using lightweight PSI Maps: refId value -> file path */
class NGSQLRefIndex : FileBasedIndexExtension<String, String>() {

  companion object {
    val KEY: ID<String, String> = ID.create("com.edxref.ng.sqlref.index")
    private val log = logger<NGSQLRefIndex>()
  }

  override fun getName(): ID<String, String> = KEY

  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

  override fun getVersion(): Int = 3

  override fun dependsOnFileContent(): Boolean = true

  override fun getInputFilter(): FileBasedIndex.InputFilter {
    return DefaultFileTypeSpecificInputFilter(com.intellij.ide.highlighter.JavaFileType.INSTANCE)
  }

  override fun getIndexer(): DataIndexer<String, String, FileContent> {
    return DataIndexer { inputData ->
      val map = mutableMapOf<String, String>()

      try {
        val psiFile = inputData.psiFile as? PsiJavaFile ?: return@DataIndexer emptyMap()

        // Get settings - this is safe during indexing as it's just reading configuration
        val project = psiFile.project
        val settings = project.getQueryRefSettings()
        val targetAnnotationFqn =
          settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }
        val attributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }

        // Extract just the simple name for quick filtering
        val simpleAnnotationName = targetAnnotationFqn.substringAfterLast('.')

        psiFile.accept(
          object : JavaRecursiveElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
              super.visitAnnotation(annotation)

              // First, quick check using the name reference element (doesn't require resolution)
              val nameElement = annotation.nameReferenceElement
              if (nameElement != null) {
                val referenceName = nameElement.referenceName

                // Quick filter by simple name
                if (referenceName == simpleAnnotationName || referenceName == targetAnnotationFqn) {
                  // Now check if this could be our annotation
                  if (
                    isPotentiallySQLRefAnnotation(
                      annotation,
                      simpleAnnotationName,
                      targetAnnotationFqn,
                    )
                  ) {
                    // Extract refId value without resolution
                    val refIdValue = extractRefIdValueSafely(annotation, attributeName)
                    if (!refIdValue.isNullOrBlank()) {
                      map[refIdValue] = inputData.file.path
                      log.debug("Indexed SQLRef: $refIdValue -> ${inputData.file.path}")
                    }
                  }
                }
              }
            }
          }
        )
      } catch (e: Exception) {
        log.error("Error indexing ${inputData.file.path}", e)
      }

      map
    }
  }

  /** Check if annotation could be SQLRef without resolving references */
  private fun isPotentiallySQLRefAnnotation(
    annotation: PsiAnnotation,
    simpleAnnotationName: String,
    targetAnnotationFqn: String,
  ): Boolean {
    val nameElement = annotation.nameReferenceElement ?: return false
    val referenceName = nameElement.referenceName ?: return false

    // Check simple name match
    if (referenceName == simpleAnnotationName) {
      // Check if it might be the right package by looking at imports
      val containingFile = annotation.containingFile as? PsiJavaFile
      if (containingFile != null) {
        // Check imports without resolution
        val importList = containingFile.importList
        if (importList != null) {
          for (importStatement in importList.importStatements) {
            val importText = importStatement.text
            if (importText.contains(targetAnnotationFqn)) {
              return true
            }
          }
        }

        // Check if it's in the same package
        val packageName = containingFile.packageName
        val annotationPackage = targetAnnotationFqn.substringBeforeLast('.')
        if (packageName == annotationPackage) {
          return true
        }
      }

      // If we can't determine, index it anyway (will be filtered later during usage)
      return true
    }

    // Check fully qualified name
    return referenceName == targetAnnotationFqn
  }

  /** Extract refId value without triggering resolution */
  private fun extractRefIdValueSafely(annotation: PsiAnnotation, attributeName: String): String? {
    try {
      // Get the parameter list
      val parameterList = annotation.parameterList
      val attributes = parameterList.attributes

      for (attribute in attributes) {
        val attrName = attribute.name

        // Check for matching attribute name or default value
        if (attrName == attributeName || attrName == null) {
          val value = attribute.value
          if (value is PsiLiteralExpression) {
            val literalValue = value.value
            if (literalValue is String) {
              return literalValue
            }
          }
        }
      }
    } catch (e: Exception) {
      log.debug("Error extracting refId value", e)
    }

    return null
  }
}
