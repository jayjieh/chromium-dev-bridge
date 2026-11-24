package dev.cdb.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.cdb.protocol.SourceLocation

/** Platform test: browser source locations resolve to project files (source navigation). */
class SourceNavigatorTest : BasePlatformTestCase() {
    fun testCandidatesFromSourceMapPaths() {
        assertEquals(listOf("src/app/dashboard.ts", "app/dashboard.ts", "dashboard.ts"),
            SourceNavigator.candidates(SourceLocation(null, "src/app/dashboard.ts", 1, 1)))
        assertEquals(listOf("static/js/main.js", "js/main.js", "main.js"),
            SourceNavigator.candidates(SourceLocation.ofUrl("http://localhost:3000/static/js/main.js?v=1", 1, 1)))
        assertEquals(listOf("src/x.ts", "x.ts"), SourceNavigator.candidates(SourceLocation(null, "../../src/x.ts", 1, 1)))
    }

    fun testResolvesMostSpecificFile() {
        myFixture.addFileToProject("src/dashboard.ts", "export const a = 1;\n")
        myFixture.addFileToProject("other/dashboard.ts", "export const b = 2;\n")
        val file = SourceNavigator.resolve(project, SourceLocation(null, "src/dashboard.ts", 1, 1))
        assertNotNull(file)
        assertTrue(file!!.path.endsWith("src/dashboard.ts"))
        assertNull(SourceNavigator.resolve(project, SourceLocation(null, "missing.ts", 1, 1)))
    }

    fun testOpensEditorAtLine() {
        myFixture.addFileToProject("src/app.ts", "line1\nline2\nline3 here\n")
        assertTrue(SourceNavigator.open(project, SourceLocation(null, "src/app.ts", 3, 7)))
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull(editor)
        assertEquals(2, editor!!.caretModel.logicalPosition.line)
        assertEquals(6, editor.caretModel.logicalPosition.column)
    }
}
