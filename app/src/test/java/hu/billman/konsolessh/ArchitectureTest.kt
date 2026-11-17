package hu.billman.konsolessh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The dependency rules between the app's packages, checked on the source
 * text. A package may use only the project packages its rule lists and none
 * of its forbidden libraries, whether through an import or a fully
 * qualified name. The root package (the composition root) is not
 * restricted, and every package may use the generated `R` class.
 */
class ArchitectureTest {

    private class Rule(
        /** Project packages it may use besides itself; null: any. */
        val mayUse: Set<String>?,
        val forbidden: List<String>,
        /** File name to the names it may still use despite the rule, each with its reason. */
        val exceptions: Map<String, List<String>> = emptyMap(),
    )

    private val rules = mapOf(
        "model" to Rule(emptySet(), listOf("android.", "androidx.", "com.jcraft.")),
        "terminal" to Rule(emptySet(), listOf("androidx.compose.", "com.jcraft.")),
        "data" to Rule(setOf("model"), listOf("com.jcraft.", "androidx.compose.")),
        "ssh" to Rule(
            setOf("model", "data"),
            listOf("android.", "androidx."),
            exceptions = mapOf(
                // Asks the platform for the local network permission state.
                "LocalNetworkAccess.kt" to listOf("android.", "androidx."),
            ),
        ),
        "session" to Rule(setOf("model", "data", "ssh"), listOf("android.", "androidx.", "com.jcraft.")),
        "sync" to Rule(
            setOf("model", "data", "ssh"),
            listOf("androidx.compose."),
            // WorkManager creates the worker, so it cannot get its dependencies through a constructor.
            exceptions = mapOf("SyncWorker.kt" to listOf("$ROOT.KonsoleApplication")),
        ),
        "service" to Rule(
            setOf("session"),
            listOf("androidx.compose.", "com.jcraft."),
            // Android creates the service, so it looks up the container itself.
            exceptions = mapOf("SshForegroundService.kt" to listOf("$ROOT.AppContainer")),
        ),
        "ui" to Rule(null, listOf("com.jcraft.", "org.bouncycastle.")),
    )

    private val sources = File("src/main/java/hu/billman/konsolessh")

    @Test
    fun `every package keeps to its dependency rule`() {
        assertTrue("sources not found from ${File("").absolutePath}", sources.isDirectory)
        val files = sources.walk().filter { it.extension == "kt" }.toList()
        val unknown = files.mapNotNull { topPackage(it) }.filter { it.isNotEmpty() && it !in rules }.toSet()
        assertEquals("packages without a rule", emptySet<String>(), unknown)
        val violations = files.flatMap { file ->
            val found = violations(topPackage(file).orEmpty(), file.name, file.readText())
            found.map { "${file.relativeTo(sources)}: $it" }
        }
        assertEquals("", violations.joinToString("\n"))
    }

    @Test
    fun `the check finds imports and qualified names`() {
        val source = """
            package hu.billman.konsolessh.session

            import hu.billman.konsolessh.ui.main.MainViewModel
            import hu.billman.konsolessh.R

            fun f() = com.jcraft.jsch.JSch() // com.jcraft.jsch in a comment is fine
            val s = "android.os.Build in a string is fine"
        """.trimIndent()
        assertEquals(
            listOf("uses package ui", "uses com.jcraft.jsch.JSch"),
            violations("session", "F.kt", source),
        )
    }

    /** The first package segment under the app's root package, "" for the root itself. */
    private fun topPackage(file: File): String? {
        val pkg = PACKAGE.find(file.readText())?.groupValues?.get(1) ?: return null
        return pkg.removePrefix(ROOT).removePrefix(".").substringBefore('.')
    }

    private fun violations(pkg: String, fileName: String, source: String): List<String> {
        val rule = rules[pkg] ?: return emptyList()
        val code = withoutCommentsAndStrings(source)
        val body = code.lines().filterNot { it.startsWith("import ") || it.startsWith("package ") }.joinToString("\n")
        val names = IMPORT.findAll(code).map { it.groupValues[1] } + QUALIFIED.findAll(body).map { it.value }
        val allowed = rule.exceptions[fileName].orEmpty()
        return names.filterNot { name -> allowed.any { name.startsWith(it) } }.flatMap { name ->
            listOfNotNull(
                projectViolation(pkg, rule, name),
                rule.forbidden.firstOrNull { name.startsWith(it) }?.let { "uses $name" },
            )
        }.distinct().toList()
    }

    private fun projectViolation(pkg: String, rule: Rule, name: String): String? {
        if (!name.startsWith("$ROOT.")) return null
        val used = name.removePrefix("$ROOT.").substringBefore('.')
        val isRootClass = used.first().isUpperCase()
        return when {
            rule.mayUse == null || used == pkg || used == "R" -> null
            isRootClass -> "uses the composition root ($used)"
            used !in rule.mayUse -> "uses package $used"
            else -> null
        }
    }

    private fun withoutCommentsAndStrings(source: String): String = source
        .replace(BLOCK_COMMENT, "")
        .replace(LINE_COMMENT, "")
        .replace(RAW_STRING, "\"\"")
        .replace(STRING, "\"\"")

    private companion object {
        const val ROOT = "hu.billman.konsolessh"
        val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
        val IMPORT = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)
        val QUALIFIED =
            Regex("""(?<![\w.])(?:android|androidx|com\.jcraft|org\.bouncycastle|hu\.billman\.konsolessh)\.[\w.]+""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val RAW_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
        val STRING = Regex(""""(\\.|[^"\\\n])*"""")
    }
}
