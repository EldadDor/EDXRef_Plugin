package com.github.edxref.query.index

import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.* // Import base PsiFile
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class QueryUtilsUsageIndex : FileBasedIndexExtension<String, String>() {

  private fun getSettings(project: Project) = project.getQueryRefSettings()

  private fun getQueryUtilsFqn(project: Project) =
    getSettings(project).queryUtilsFqn.ifBlank { "com.example.QueryUtils" }

  private fun getQueryUtilsMethodName(project: Project) =
    getSettings(project).queryUtilsMethodName.ifBlank { "getQuery" }

  companion object {
    val KEY: ID<String, String> = ID.create("com.edxref.queryutils.usage.index")
    private val log: Logger = logger<QueryUtilsUsageIndex>()
    // Consider adding expected method/qualifier names here if they are relatively fixed
    // or accept that the index might contain slightly more than needed, to be filtered later.
    // Example
  }

  override fun getName(): ID<String, String> = KEY

  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

  override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

  override fun getVersion(): Int = 2 // Increment version due to logic change

  override fun dependsOnFileContent(): Boolean = true

  override fun getInputFilter(): FileBasedIndex.InputFilter {
    // Index only Java files
    return DefaultFileTypeSpecificInputFilter(com.intellij.ide.highlighter.JavaFileType.INSTANCE)
  }

  override fun getIndexer(): DataIndexer<String, String, FileContent> {
    return DataIndexer { inputData ->
      val map = mutableMapOf<String, String>()
      val psiFile = inputData.psiFile
      val project = psiFile.project

      // Check dumb mode to avoid IndexNotReadyException
      val (queryUtilsFqn, queryUtilsMethodName) =
        if (DumbService.isDumb(project)) {
          // Use defaults during dumb mode
          "com.example.QueryUtils" to "getQuery"
        } else {
          // Use settings when project is ready
          val settings = project.getQueryRefSettings()
          val fqn = settings.queryUtilsFqn.ifBlank { "com.example.QueryUtils" }
          val method = settings.queryUtilsMethodName.ifBlank { "getQuery" }
          fqn to method
        }
      psiFile.accept(
        object : JavaRecursiveElementVisitor() {
          override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
            //						super.visitMethodCallExpression(call)
            //
            //						val methodExpr = call.methodExpression
            //						if (methodExpr.referenceName == queryUtilsMethodName) {

            //							override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
            super.visitMethodCallExpression(call)

            val methodExpr = call.methodExpression
            val actualMethodName = methodExpr.referenceName

            log.debug("=== Method Call Analysis ===")
            log.debug("File: ${inputData.file.name}")
            log.debug("Method call text: '${call.text}'")
            log.debug("Actual method name: '$actualMethodName'")
            log.debug("Expected method name: '$queryUtilsMethodName'")
            log.debug("Method names match: ${actualMethodName == queryUtilsMethodName}")

            if (methodExpr.referenceName == queryUtilsMethodName) {
              val qualifierExpr = methodExpr.qualifierExpression
              val qualifierText = qualifierExpr?.text
              val qualifierType = qualifierExpr?.type?.canonicalText

              log.debug("--- Qualifier Analysis ---")
              log.debug("Qualifier expression: $qualifierExpr")
              log.debug("Qualifier text: '$qualifierText'")
              log.debug("Qualifier type: '$qualifierType'")
              log.debug("Expected qualifier text 1: 'queryUtils'")
              log.debug("Expected qualifier text 2: '$queryUtilsFqn'")
              log.debug("Qualifier matches 'queryUtils': ${qualifierText == "queryUtils"}")
              log.debug("Qualifier matches FQN: ${qualifierText == queryUtilsFqn}")

              // Additional type checking
              if (qualifierExpr != null) {
                log.debug("Qualifier class: ${qualifierExpr.javaClass.simpleName}")
                if (qualifierExpr is PsiReferenceExpression) {
                  val resolved = qualifierExpr.resolve()
                  log.debug("Qualifier resolves to: $resolved")
                  log.debug("Resolved element type: ${resolved?.javaClass?.simpleName}")
                }
              }

              if (qualifierText == "queryUtils" || qualifierText == queryUtilsFqn) {
                val args = call.argumentList.expressions
                log.debug("--- Arguments Analysis ---")
                log.debug("Arguments count: ${args.size}")
                log.debug("Expected arguments count: 1")

                if (args.isNotEmpty()) {
                  args.forEachIndexed { index, arg ->
                    log.debug("Argument $index: '${arg.text}'")
                    log.debug("Argument $index type: ${arg.javaClass.simpleName}")
                    log.debug(
                      "Argument $index is PsiLiteralExpression: ${arg is PsiLiteralExpression}"
                    )
                  }
                }

                if (args.size == 1 && args[0] is PsiLiteralExpression) {
                  val literal = args[0] as PsiLiteralExpression
                  val literalValue = literal.value
                  val queryId = literalValue as? String

                  log.debug("--- Literal Analysis ---")
                  log.debug("Literal text: '${literal.text}'")
                  log.debug("Literal value: '$literalValue'")
                  log.debug("Literal value type: ${literalValue?.javaClass?.simpleName}")
                  log.debug("Query ID: '$queryId'")
                  log.debug("Query ID is valid: ${!queryId.isNullOrBlank()}")

                  if (!queryId.isNullOrBlank()) {
                    log.debug("✅ SUCCESS: Found valid QueryUtils usage")
                    log.debug("   Query ID: '$queryId'")
                    log.debug("   File: ${inputData.file.name}")
                    log.debug("   Full call: '${call.text}'")

                    map[queryId] = inputData.file.path
                  } else {
                    log.debug("❌ FAILED: Query ID is null or blank")
                  }
                } else {
                  log.debug("❌ FAILED: Arguments validation failed")
                  if (args.size != 1) {
                    log.debug("   Reason: Expected 1 argument, found ${args.size}")
                  }
                  if (args.isNotEmpty() && args[0] !is PsiLiteralExpression) {
                    log.debug("   Reason: First argument is not a literal expression")
                  }
                }
              } else {
                log.debug("❌ FAILED: Qualifier doesn't match expected values")
                log.debug("   Expected: 'queryUtils' or '$queryUtilsFqn'")
                log.debug("   Actual: '$qualifierText'")
              }
            } else {
              log.debug("❌ SKIP: Method name doesn't match")
              if (actualMethodName != null) {
                log.debug(
                  "   Checking if method name contains expected: ${actualMethodName.contains(queryUtilsMethodName ?: "")}"
                )
              }
            }
            log.debug("=== End Method Call Analysis ===\n")
          }
          /*if (qualifierText == "queryUtils" || qualifierText == queryUtilsFqn) {
          	val args = call.argumentList.expressions
          	if (args.size == 1 && args[0] is PsiLiteralExpression) {
          		val literal = args[0] as PsiLiteralExpression
          		val queryId = literal.value as? String
          		if (!queryId.isNullOrBlank()) {
          			log.debug(
          				"Indexer found potential usage: ID='$queryId' in ${inputData.file.name}"
          			)
          			map[queryId] = inputData.file.path
          		}
          	}
          }*/
          //						}
          //					}
        }
      )
      map
    }
  }
}
