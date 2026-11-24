package dev.cdb.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.cdb.protocol.SourceLocation

/**
 * Maps browser source locations (original source-map paths or script URLs) to files in the project and opens them.
 * Matching: longest path-suffix match among project files with the same file name.
 */
object SourceNavigator {
    /** Candidate relative paths, most specific first: "src/app/x.ts" -> ["src/app/x.ts", "app/x.ts", "x.ts"]. */
    fun candidates(location: SourceLocation): List<String> {
        // A bare file name derived from a script URL is less specific than the URL path itself.
        val url = location.url()
        val raw = when {
            url != null && (location.file() == null || location.file() == SourceLocation.fileName(url)) -> url
            else -> location.file()
        } ?: return emptyList()
        var path = raw.substringBefore('?').substringBefore('#').replace('\\', '/')
        if (path.contains("://")) path = path.substringAfter("://").substringAfter('/')
        path = path.trimStart('/').removePrefix("./")
        while (path.startsWith("../")) path = path.removePrefix("../")
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        return parts.indices.map { parts.subList(it, parts.size).joinToString("/") }
    }

    /** Finds the best matching project file. Requires a read action (smart mode). */
    fun resolve(project: Project, location: SourceLocation): VirtualFile? {
        val candidates = candidates(location)
        val fileName = candidates.lastOrNull() ?: return null
        val files = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
        if (files.isEmpty()) return null
        return files.maxByOrNull { file -> score(file.path.replace('\\', '/'), candidates) }
    }

    internal fun score(path: String, candidates: List<String>): Int {
        for ((i, c) in candidates.withIndex()) {
            if (path.endsWith("/$c") || path == c) return candidates.size - i
        }
        return 0
    }

    /** Opens the file at the location's line and column (1-based in the protocol). Returns false if not found. */
    fun open(project: Project, location: SourceLocation): Boolean {
        val file = ReadAction.compute<VirtualFile?, RuntimeException> { resolve(project, location) } ?: return false
        val line = ((location.line() ?: 1) - 1).coerceAtLeast(0)
        val column = ((location.column() ?: 1) - 1).coerceAtLeast(0)
        OpenFileDescriptor(project, file, line, column).navigate(true)
        return true
    }
}
