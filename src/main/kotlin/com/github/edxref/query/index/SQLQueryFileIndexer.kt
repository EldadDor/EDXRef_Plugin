package com.github.edxref.query.index

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.util.indexing.*
import com.intellij.util.io.*
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.PsiManager

class SQLQueryFileIndexer : FileBasedIndexExtension<String, String>() {
    companion object {
        val KEY = ID.create<String, String>("com.edxref.sqlquery.index")
    }

    override fun getName() = KEY

    override fun getVersion() = 1

    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE)

    override fun dependsOnFileContent() = true

    override fun getIndexer() = DataIndexer<String, String, FileContent> { inputData ->
        val map = mutableMapOf<String, String>()
        val psiFile = PsiManager.getInstance(inputData.project).findFile(inputData.file)
        if (psiFile is XmlFile) {
            val root = psiFile.rootTag
            if (root?.name == "Queries") {
                root.findSubTags("query").forEach { tag ->
                    tag.getAttributeValue("id")?.let { id ->
                        map[id] = inputData.file.path
                    }
                }
            }
        }
        map
    }

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE
}
