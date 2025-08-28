package com.github.edxref.query.ng.index

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/** Optimized index for QueryUtils.getQuery() calls Maps: queryId -> file path */
class NGQueryUtilsIndex : FileBasedIndexExtension<String, String>() {

  companion object {
    val KEY: ID<String, String> = ID.create("com.edxref.ng.queryutils.index")
    private val log = logger<NGQueryUtilsIndex>()
  }

  override fun getName(): ID<String, String> = KEY

  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

  override fun getVersion(): Int = 3 // Incremented for the fix

  override fun dependsOnFileContent(): Boolean = true

  override fun getInputFilter(): FileBasedIndex.InputFilter {
    return object :
      DefaultFileTypeSpecificInputFilter(com.intellij.ide.highlighter.JavaFileType.INSTANCE) {
      override fun acceptInput(file: VirtualFile): Boolean {
        return super.acceptInput(file) &&
          file.name.endsWith(".java") &&
          !file.path.contains("/test/") &&
          !file.path.contains("/.gradle/") &&
          !file.path.contains("/.m2/")
      }
    }
  }

  override fun getIndexer(): DataIndexer<String, String, FileContent> {
    return DataIndexer { inputData ->
      val map = mutableMapOf<String, String>()

      try {
        // Quick text-based pre-check
        val content = inputData.contentAsText.toString()
        if (!content.contains("getQuery") || !content.contains("queryUtils")) {
          return@DataIndexer emptyMap()
        }

        val psiFile = inputData.psiFile as? PsiJavaFile ?: return@DataIndexer emptyMap()

        psiFile.accept(OptimizedQueryUtilsVisitor(map, inputData.file.path))
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

  private class OptimizedQueryUtilsVisitor(
    private val resultMap: MutableMap<String, String>,
    private val filePath: String,
  ) : JavaRecursiveElementVisitor() {

    private var foundCalls = 0
    private val maxCallsPerFile = 100

    override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
      try {
        val methodExpr = call.methodExpression

        // Quick name check
        if (methodExpr.referenceName == "getQuery") {
          // Quick qualifier check (text-based, no resolution)
          val qualifierText = methodExpr.qualifierExpression?.text?.lowercase()
          if (qualifierText?.contains("queryutils") == true) {

            // Get first argument if it's a string literal
            val args = call.argumentList.expressions
            if (args.isNotEmpty() && args[0] is PsiLiteralExpression) {
              val literal = args[0] as PsiLiteralExpression
              val queryId = literal.value as? String
              if (!queryId.isNullOrBlank()) {
                resultMap[queryId] = filePath
                foundCalls++

                if (log.isDebugEnabled) {
                  log.debug("Indexed QueryUtils usage: $queryId -> $filePath")
                }

                if (foundCalls >= maxCallsPerFile) {
                  return
                }
              }
            }
          }
        }
      } catch (e: ProcessCanceledException) {
        throw e
      } catch (e: ReadAction.CannotReadException) {
        throw e
      } catch (e: Exception) {
        // Ignore individual call processing errors
      }

      // Continue visiting if we haven't hit the limit
      if (foundCalls < maxCallsPerFile) {
        super.visitMethodCallExpression(call)
      }
    }
  }
}
