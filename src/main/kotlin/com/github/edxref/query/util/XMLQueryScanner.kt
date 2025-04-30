package com.github.edxref.query.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.roots.ProjectFileIndex

object XMLQueryScanner {
    fun findQueryFiles(project: Project): Collection<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (file.path.matches(Regex(""".*/resources/queries/.*-queries\.xml$"""))) {
                files.add(file)
            }
            true
        }
        return files
    }
}
