package com.github.edxref.query.ng.index

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/** Simple index for QueryUtils.getQuery() calls Maps: queryId -> file path */
class NGQueryUtilsIndex : FileBasedIndexExtension<String, String>() {

  companion object {
    val KEY: ID<String, String> = ID.create("com.edxref.ng.queryutils.index")
    private val log = logger<NGQueryUtilsIndex>()
  }

  override fun getName(): ID<String, String> = KEY

  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

  override fun getVersion(): Int = 1

  override fun dependsOnFileContent(): Boolean = true

  override fun getInputFilter(): FileBasedIndex.InputFilter {
    return DefaultFileTypeSpecificInputFilter(JavaFileType.INSTANCE)
  }

  override fun getIndexer(): DataIndexer<String, String, FileContent> {
    return DataIndexer { inputData ->
      val map = mutableMapOf<String, String>()

      try {
        val psiFile = inputData.psiFile as? PsiJavaFile ?: return@DataIndexer emptyMap()

        psiFile.accept(
          object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
              super.visitMethodCallExpression(call)

              val methodExpr = call.methodExpression

              // Simple heuristic: method name is "getQuery"
              if (methodExpr.referenceName == "getQuery") {
                // Check if qualifier contains "queryUtils" (case-insensitive)
                val qualifierText = methodExpr.qualifierExpression?.text?.lowercase()
                if (qualifierText?.contains("queryutils") == true) {
                  // Get first argument if it's a string literal
                  val args = call.argumentList.expressions
                  if (args.isNotEmpty() && args[0] is PsiLiteralExpression) {
                    val literal = args[0] as PsiLiteralExpression
                    val queryId = literal.value as? String
                    if (!queryId.isNullOrBlank()) {
                      map[queryId] = inputData.file.path
                      log.debug("Indexed QueryUtils usage: $queryId -> ${inputData.file.path}")
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
}
