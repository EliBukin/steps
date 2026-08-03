package com.example.stepsplit

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every user-facing string must exist in both `values/strings.xml` (Hebrew, the default locale)
 * and `values-en/strings.xml` (English) - a key present in only one would silently fall back to
 * the other language rather than fail the build, so nothing else would catch it. Reads the actual
 * resource files directly (not a hand-maintained list of expected keys) so this stays correct as
 * strings are added or removed over time - see [StepSplitDatabaseMigrationTest][com.example.stepsplit.data.local.StepSplitDatabaseMigrationTest]
 * for the same file-reading-relative-to-the-`app/`-module-root approach used elsewhere in this
 * test suite.
 */
class LocalizationResourcesTest {

    @Test
    fun `Hebrew and English string resources define exactly the same set of keys`() {
        val defaultKeys = stringKeysIn("src/main/res/values/strings.xml")
        val englishKeys = stringKeysIn("src/main/res/values-en/strings.xml")

        val missingFromEnglish = defaultKeys - englishKeys
        val missingFromDefault = englishKeys - defaultKeys

        assertTrue("Keys present in values/strings.xml but missing from values-en/strings.xml: $missingFromEnglish", missingFromEnglish.isEmpty())
        assertTrue("Keys present in values-en/strings.xml but missing from values/strings.xml: $missingFromDefault", missingFromDefault.isEmpty())
    }

    @Test
    fun `neither locale's string resource file is empty`() {
        assertTrue(stringKeysIn("src/main/res/values/strings.xml").isNotEmpty())
        assertTrue(stringKeysIn("src/main/res/values-en/strings.xml").isNotEmpty())
    }

    private fun stringKeysIn(relativePath: String): Set<String> {
        val file = File(relativePath)
        check(file.exists()) {
            "Expected $relativePath to exist - this test must run with the app/ module directory " +
                "as the working directory (true for `gradlew testDebugUnitTest`)."
        }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildSet {
            for (i in 0 until nodes.length) {
                add(nodes.item(i).attributes.getNamedItem("name").nodeValue)
            }
        }
    }
}
