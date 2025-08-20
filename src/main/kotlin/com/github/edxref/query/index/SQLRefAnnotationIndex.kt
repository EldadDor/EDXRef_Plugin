/*
 * User: eadno1
 * Date: 20/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */
package com.github.edxref.query.index


import com.github.edxref.query.settings.QueryRefSettings.Companion.getQueryRefSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiJavaFile
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class SQLRefAnnotationIndex : FileBasedIndexExtension<String, String>() {

	companion object {
		val KEY: ID<String, String> = ID.create("com.edxref.sqlref.annotation.index")
		private val log: Logger = logger<SQLRefAnnotationIndex>()
	}

	override fun getName(): ID<String, String> = KEY

	override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

	override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE

	override fun getVersion(): Int = 1

	override fun dependsOnFileContent(): Boolean = true

	override fun getInputFilter(): FileBasedIndex.InputFilter {
		// Index only Java files
		return DefaultFileTypeSpecificInputFilter(com.intellij.ide.highlighter.JavaFileType.INSTANCE)
	}

	override fun getIndexer(): DataIndexer<String, String, FileContent> {
		return DataIndexer { inputData ->
			val map = mutableMapOf<String, String>()
			val psiFile = inputData.psiFile

			if (psiFile is PsiJavaFile) {
				val project = psiFile.project
				val settings = project.getQueryRefSettings()
				val annotationFqn = settings.sqlRefAnnotationFqn.ifBlank { "com.github.edxref.SQLRef" }
				val attributeName = settings.sqlRefAnnotationAttributeName.ifBlank { "refId" }

				psiFile.accept(object : JavaRecursiveElementVisitor() {
					override fun visitAnnotation(annotation: PsiAnnotation) {
						super.visitAnnotation(annotation)

						// Check if this is an SQLRef annotation
						if (annotation.qualifiedName == annotationFqn) {
							val refIdValue = annotation.findAttributeValue(attributeName)?.text?.replace("\"", "")

							if (!refIdValue.isNullOrBlank()) {
								// Index the refId -> filePath
								log.debug("Indexer found SQLRef annotation: refId='$refIdValue' in ${inputData.file.name}")
								map[refIdValue] = inputData.file.path
							}
						}
					}
				})
			}

			map
		}
	}
}
