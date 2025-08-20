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
			val (queryUtilsFqn, queryUtilsMethodName) = if (DumbService.isDumb(project)) {
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
						super.visitMethodCallExpression(call)

						val methodExpr = call.methodExpression
						if (methodExpr.referenceName == queryUtilsMethodName) {
							val qualifierText = methodExpr.qualifierExpression?.text

							if (qualifierText == "queryUtils" || qualifierText == queryUtilsFqn) {
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
							}
						}
					}
				}
			)
			map
		}
	}
}
