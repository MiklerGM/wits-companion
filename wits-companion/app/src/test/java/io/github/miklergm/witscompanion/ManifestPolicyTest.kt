package io.github.miklergm.witscompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the manifest and docs/security.md §3.6 in step.
 *
 * This is a public repository whose security document is the trust story: a reader decides
 * whether to run this on a car they drive by reading it. When the table listed three of the
 * eight permissions actually requested — omitting `MANAGE_ACTIVITY_TASKS`, `REMOVE_TASKS`,
 * `WRITE_SECURE_SETTINGS` and the Wi-Fi trio — the document was not merely incomplete, it
 * understated the app's reach. The manifest had drifted against itself too, still claiming
 * `WRITE_SECURE_SETTINGS` was "deliberately NOT requested" eighteen lines above requesting it.
 *
 * Both were found by writing this test, which is the argument for having it.
 */
class ManifestPolicyTest {

    private fun repoFile(vararg candidates: String): File? =
        candidates.map(::File).firstOrNull { it.exists() }

    private fun manifest(): String = repoFile(
        "src/main/AndroidManifest.xml",
        "app/src/main/AndroidManifest.xml",
        "wits-companion/app/src/main/AndroidManifest.xml",
    ).let {
        assertNotNull("app manifest not found from ${File(".").absolutePath}", it)
        it!!.readText()
    }

    private fun securityDoc(): String = repoFile(
        "../docs/security.md", "../../docs/security.md", "docs/security.md",
    ).let {
        assertNotNull("docs/security.md not found from ${File(".").absolutePath}", it)
        it!!.readText()
    }

    private fun declaredPermissions(xml: String): List<String> =
        Regex("""<uses-permission[^>]*android:name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), ""))
            .map { it.groupValues[1].substringAfterLast('.') }
            .toList()

    @Test
    fun `every requested permission is documented in security md`() {
        val doc = securityDoc()
        val undocumented = declaredPermissions(manifest()).filter { !doc.contains(it) }
        assertTrue(
            "requested in the manifest but absent from docs/security.md §3.6: $undocumented",
            undocumented.isEmpty(),
        )
    }

    @Test
    fun `the manifest does not request what it claims to decline`() {
        // The exact contradiction this test was written after finding.
        val xml = manifest()
        val requested = declaredPermissions(xml).toSet()
        val declinedClaim = Regex("""Deliberately NOT requested:(.*?)-->""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1).orEmpty()
        val contradictions = requested.filter { declinedClaim.contains(it) }
        assertTrue(
            "the manifest claims to decline these while requesting them: $contradictions",
            contradictions.isEmpty(),
        )
    }

    @Test
    fun `the permission policy the manifest states is actually held`() {
        // The header block promises these are absent; a future edit must not quietly add one.
        val requested = declaredPermissions(manifest()).toSet()
        listOf(
            "INTERNET",              // nothing can be uploaded or remotely triggered
            "SYSTEM_ALERT_WINDOW",   // no overlays, especially not over the reverse camera
            "QUERY_ALL_PACKAGES",    // explicit <queries> instead
            "INTERNAL_SYSTEM_WINDOW",
            "STATUS_BAR_SERVICE",
        ).forEach {
            assertTrue("$it is declared, but both the manifest and security.md say it is not", it !in requested)
        }
    }

    @Test
    fun `no component is exported except the launcher activity`() {
        // security.md §3.3 claims no exported receiver, service or provider performs a
        // dangerous action. Exporting something new should have to be a deliberate edit here.
        val xml = manifest()
        val exported = Regex("""<(activity|service|receiver|provider)\b[^>]*?android:exported="true"""",
            RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(
            "only the launcher activity may be exported, found: $exported",
            listOf("activity"), exported,
        )
    }
}
