package com.github.edxref.query.index

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.impl.source.PsiJavaFileImpl
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import com.intellij.util.indexing.FileContent
import com.intellij.openapi.vfs.VirtualFile

class QueryUtilsUsageIndex : FileBasedIndexExtension<String, String>() {
    companion object {
        val KEY: ID<String, String> = ID.create("com.edxref.queryutils.usage.index")
        private val log: Logger = logger<QueryUtilsUsageIndex>()
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
            val psiFile = inputData.psiFile
            if (psiFile is PsiJavaFileImpl) {
                psiFile.accept(object : com.intellij.psi.JavaRecursiveElementVisitor() {
                    override fun visitLiteralExpression(expression: PsiLiteralExpression) {
                        super.visitLiteralExpression(expression)
                        val value = expression.value
                        if (value is String) {
                            val methodCall = expression.parent?.parent as? PsiMethodCallExpression ?: return
                            val methodExpr = methodCall.methodExpression
                            val methodName = methodExpr.referenceName
                            val qualifierExpr = methodExpr.qualifierExpression as? PsiReferenceExpression
                            val qualifierType = qualifierExpr?.type
                            val qualifierFqn = qualifierType?.canonicalText
                            // You may want to get the FQN from settings, but for indexer, hardcode or use a static list
                            if (methodName == "getQuery" && qualifierFqn == "com.example.QueryUtils") {
                                map[value] = inputData.file.path
                            }
                        }
                    }
                })
            }
            map
        }
    }
}
