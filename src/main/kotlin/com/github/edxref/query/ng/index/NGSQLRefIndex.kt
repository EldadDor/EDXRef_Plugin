package com.github.edxref.query.ng.index

/*
 * User: eadno1
 * Date: 22/08/2025 
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA. 
 */
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.*
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * Simple index for @SQLRef annotations
 * Maps: refId value -> file path
 */
class NGSQLRefIndex : FileBasedIndexExtension<String, String>() {

	companion object {
		val KEY: ID<String, String> = ID.create("com.edxref.ng.sqlref.index")
		private val log = logger<NGSQLRefIndex>()

		// Default values for SQLRef annotation
		const val DEFAULT_ANNOTATION_FQN = "com.github.edxref.SQLRef"
		const val DEFAULT_ATTRIBUTE_NAME = "refId"
	}

	override fun getName(): ID<String, String> = KEY

	override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

	override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

	override fun getVersion(): Int = 1

	override fun dependsOnFileContent(): Boolean = true

	override fun getInputFilter(): FileBasedIndex.InputFilter {
		return DefaultFileTypeSpecificInputFilter(com.intellij.ide.highlighter.JavaFileType.INSTANCE)
	}

	override fun getIndexer(): DataIndexer<String, String, FileContent> {
		return DataIndexer { inputData ->
			val map = mutableMapOf<String, String>()

			try {
				val psiFile = inputData.psiFile as? PsiJavaFile ?: return@DataIndexer emptyMap()

				psiFile.accept(object : JavaRecursiveElementVisitor() {
					override fun visitAnnotation(annotation: PsiAnnotation) {
						super.visitAnnotation(annotation)

						// Simple check for SQLRef annotation
						val qualifiedName = annotation.qualifiedName
						if (qualifiedName == DEFAULT_ANNOTATION_FQN ||
							qualifiedName?.endsWith(".SQLRef") == true) {

							// Extract refId value
							val refIdValue = extractRefIdValue(annotation)
							if (!refIdValue.isNullOrBlank()) {
								map[refIdValue] = inputData.file.path
								log.debug("Indexed SQLRef: $refIdValue -> ${inputData.file.path}")
							}
						}
					}
				})
			} catch (e: Exception) {
				log.error("Error indexing ${inputData.file.path}", e)
			}

			map
		}
	}

	private fun extractRefIdValue(annotation: PsiAnnotation): String? {
		// Try to get refId attribute value
		val refIdAttr = annotation.findAttributeValue(DEFAULT_ATTRIBUTE_NAME)
		if (refIdAttr is PsiLiteralExpression) {
			return refIdAttr.value as? String
		}

		// If it's the default value (single value annotation)
		val defaultValue = annotation.findAttributeValue(null)
		if (defaultValue is PsiLiteralExpression) {
			return defaultValue.value as? String
		}

		return null
	}
}
