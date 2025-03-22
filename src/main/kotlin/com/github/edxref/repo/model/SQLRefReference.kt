package com.github.edxref.repo.model

import com.google.common.collect.HashBasedTable
import com.google.common.collect.Lists
import com.google.common.collect.MapMaker
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import org.jetbrains.annotations.NotNull

class SQLRefReference(
		private var sqlRefId: String,
		private val project: Project
) : Comparable<String> {

	private val xmlFiles: MutableMap<String, List<VirtualFile>> = MapMaker().initialCapacity(0).makeMap()
	private val classFiles: MutableMap<String, List<VirtualFile>> = MapMaker().initialCapacity(0).makeMap()
	private val xmlQueryElements: MutableList<PsiElement> = mutableListOf()
	private val classAnnoElements: MutableList<PsiElement> = mutableListOf()
	private val xmlSmartPointersElements: MutableList<SmartPsiElementPointer<PsiElement>> = mutableListOf()
	private val classSmartPointersElements: MutableList<SmartPsiElementPointer<PsiElement>> = mutableListOf()
	private val utilClassSmartPointersElements: HashBasedTable<String, String, SmartPsiElementPointer<PsiElement>> = HashBasedTable.create()
	private val classPropertiedMethods: MutableMap<String, Map<String, PsiMethod>> = MapMaker().initialCapacity(0).makeMap()
	private val sqlSelectedColumns: MutableMap<String, String> = MapMaker().initialCapacity(0).makeMap()
	private val sqlWhereParams: MutableMap<String, String> = MapMaker().initialCapacity(0).makeMap()

	fun renameOnlySQLRefId(sqlRefId: String) {
		this.sqlRefId = sqlRefId
	}

	fun renameSQLRefId(sqlRefId: String) {
		this.sqlRefId = sqlRefId
		val attributeId = ""
//			AnnoRefConfigSettings.getInstance(project).annoRefState.ANNOREF_ANNOTATION_ATTRIBUTE_ID

		classAnnoElements.forEach { classAnnoElement ->
//				JamCommonUtil.setAnnotationAttributeValue(classAnnoElement as PsiAnnotation, attributeId, sqlRefId)
		}

		xmlQueryElements.forEach { xmlQueryElement ->
				(xmlQueryElement as PsiLanguageInjectionHost).updateText(sqlRefId)
		}

		utilClassSmartPointersElements.values().forEach { psiElement ->
				psiElement.element?.let { element ->
//				(element as PsiLanguageInjectionHost).updateText(StringUtils.quote(sqlRefId))
		}
		}
	}

	fun addClassInformation(classVF: VirtualFile, psiElement: PsiElement): SQLRefReference {
		if (addClassFile(classVF.name, classVF)) {
			classAnnoElements.add(psiElement)
			classSmartPointersElements.add(createAnnoRefSmartPointer(psiElement))
		}
		return this
	}

	fun addUtilClassCallInformation(refId: String, refToRef: String, classVF: VirtualFile, psiElement: PsiElement): SQLRefReference {
		if (addClassFile(classVF.name, classVF)) {
			val annoRefSmartPointer = createAnnoRefSmartPointer(psiElement)
			utilClassSmartPointersElements.put(refId, refToRef, annoRefSmartPointer)
		}
		return this
	}

	fun addXmlInformation(xmlVF: VirtualFile, psiElement: PsiElement): SQLRefReference {
		if (addXmlFile(xmlVF.name, xmlVF)) {
			xmlQueryElements.add(psiElement)
			xmlSmartPointersElements.add(createAnnoRefSmartPointer(psiElement))
		}
		return this
	}

	fun assignMethodPropertyInformationMap(methodPropertiesMap: Map<String, Map<String, PsiMethod>>): SQLRefReference {
		classPropertiedMethods.putAll(methodPropertiesMap)
		return this
	}

	fun assignSqlSelectColumnsInformationMap(methodPropertiesMap: Map<String, String>): SQLRefReference {
		sqlSelectedColumns.putAll(methodPropertiesMap)
		return this
	}

	fun addSqlSelectColumnToInformationMap(columnName: String): SQLRefReference {
		sqlSelectedColumns[columnName] = columnName
		return this
	}

	fun addSqlWhereParamsToInformationMap(paramName: String): SQLRefReference {
		sqlWhereParams[paramName] = paramName
		return this
	}

	fun isVoToXmlValidModel(): Boolean {
		if (classPropertiedMethods.isNotEmpty()) {
			val settersMap = classPropertiedMethods["SETTER_PROPERTY"] ?: return true
			if (settersMap.keys.any { !sqlWhereParams.containsKey(it) }) {
				return false
			}

			val gettersMap = classPropertiedMethods["GETTER_PROPERTY"] ?: return true
			if (gettersMap.keys.any { !sqlSelectedColumns.containsKey(it) }) {
				return false
			}
		}
		return true
	}

	private fun createAnnoRefSmartPointer(annoElement: PsiElement): SmartPsiElementPointer<PsiElement> =
	SmartPointerManager.getInstance(annoElement.project).createSmartPsiElementPointer(annoElement)

	fun addUtilClassCallInformation(refId: String, psiMethodElement: PsiElement): SQLRefReference {
		if (log.isDebugEnabled) {
			log.info("addUtilClassCallInformation(): refId=$refId")
		}
		createAnnoRefSmartPointer(psiMethodElement)
		return this
	}

	fun addXmlFile(xmlFileName: String, xmlFile: VirtualFile): Boolean {
		if (!xmlFiles.containsKey(xmlFileName)) {
			xmlFiles[xmlFileName] = Lists.newArrayList(xmlFile)
			return true
		}
		return false
	}

	fun addClassFile(classFileName: String, classFile: VirtualFile): Boolean {
		if (!classFiles.containsKey(classFileName)) {
			classFiles[classFileName] = Lists.newArrayList(classFile)
			return true
		}
		return false
	}

	val hasSomeElements: Boolean
	get() = xmlSmartPointersElements.isNotEmpty() ||
			classSmartPointersElements.isNotEmpty() ||
			utilClassSmartPointersElements.isEmpty.not()

	val collectiveSize: Int
	get() = xmlSmartPointersElements.size +
			classSmartPointersElements.size +
			utilClassSmartPointersElements.size()

	override fun toString(): String {
		return "SQLRefReference(sqlRefId='$sqlRefId', xmlFiles=$xmlFiles, classFiles=$classFiles, " +
				"xmlQueryElements=$xmlQueryElements, classAnnoElements=$classAnnoElements)"
	}

	override fun compareTo(@NotNull refID: String): Int {
		val thisLength = sqlRefId.substring(0, sqlRefId.length).toByteArray().size
		val otherLength = refID.substring(0, refID.length).toByteArray().size

		return when {
			otherLength < thisLength -> -1
			otherLength == thisLength -> 0
            else -> 1
		}
	}

	companion object {
		private val log = Logger.getInstance(SQLRefReference::class.java)
	}
}
