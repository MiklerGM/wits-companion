package io.github.miklergm.witscompanion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard: the Signal Explorer must remain observation-only.
 *
 * This scans the actual source of the signalexplorer package for forbidden
 * transmit/mutate constructs. It is deliberately a source scan rather than a mock-based
 * test, because the requirement is "this code cannot transmit", not "this code did not
 * transmit in one scenario".
 */
class NoTransmitGuardTest {

    private fun explorerSources(): List<File> {
        val roots = listOf(
            File("src/main/java/io/github/miklergm/witscompanion/signalexplorer"),
            File("app/src/main/java/io/github/miklergm/witscompanion/signalexplorer"),
        )
        val dir = roots.firstOrNull { it.isDirectory }
        assertTrue("signalexplorer sources not found (cwd=${File(".").absolutePath})", dir != null)
        return dir!!.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    /** Strip // and /* */ comments so documentation mentioning a name is not a hit. */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `explorer never references the raw MCU injection actions`() {
        val forbidden = listOf(
            "ACTION_CAN_CENTER_REV",
            "ACTION_COMMON_CMD_REV",
            "com.can.ACTION_CAN_CENTER_REV",
            "com.center.ACTION_COMMON_CMD_REV",
            "0x300000",
        )
        explorerSources().forEach { f ->
            val body = stripComments(f.readText())
            forbidden.forEach { needle ->
                assertTrue(
                    "${f.name} references forbidden MCU injection token '$needle'",
                    !body.contains(needle),
                )
            }
        }
    }

    @Test
    fun `explorer never sends a broadcast or starts an activity`() {
        val forbidden = listOf(
            "sendBroadcast", "sendBroadcastAsUser", "sendOrderedBroadcast",
            "startActivity", "startService", "startForegroundService",
        )
        explorerSources().forEach { f ->
            val body = stripComments(f.readText())
            forbidden.forEach { needle ->
                assertTrue(
                    "${f.name} contains forbidden transmit call '$needle'",
                    !body.contains(needle),
                )
            }
        }
    }

    @Test
    fun `explorer never mutates volume settings or properties`() {
        val forbidden = listOf(
            "setStreamVolume", "adjustStreamVolume", "adjustSuggestedStreamVolume",
            "adjustVolume", "setRingerMode", "setMicrophoneMute", "setMode(",
            "Settings.System.put", "Settings.Global.put", "Settings.Secure.put",
            "SystemProperties.set", "\"ctl.start\"",
            "sendKeyDownUpSync", "sendKeySync", "injectInputEvent", "Instrumentation(",
        )
        explorerSources().forEach { f ->
            val body = stripComments(f.readText())
            forbidden.forEach { needle ->
                assertTrue(
                    "${f.name} contains forbidden mutation call '$needle'",
                    !body.contains(needle),
                )
            }
        }
    }

    @Test
    fun `explorer sources exist and are non-trivial`() {
        val files = explorerSources()
        assertTrue("expected several explorer files, found ${files.size}", files.size >= 5)
        assertTrue(files.sumOf { it.readText().length } > 10_000)
    }
}
