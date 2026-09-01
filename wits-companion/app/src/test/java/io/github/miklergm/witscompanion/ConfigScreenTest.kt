package io.github.miklergm.witscompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guards for the configuration screens.
 *
 * These are read at arm's length in a car, often moving, and they started life as a debugging
 * surface: bare checkboxes, default-height buttons, 14sp body text. The widgets are built in
 * code rather than layout XML and cannot be instantiated without `MainActivity`, so this checks
 * the source — enough to catch a control being added back in the old shape.
 */
class ConfigScreenTest {

    private val sections: String = listOf(
        "src/main/java/io/github/miklergm/witscompanion/ui/Sections.kt",
        "app/src/main/java/io/github/miklergm/witscompanion/ui/Sections.kt",
    ).first { File(it).exists() }.let { File(it).readText() }

    @Test
    fun `no bare checkbox is left in the settings`() {
        // A CheckBox's tappable area is its label's height and its box is a few millimetres
        // across on this display. Every toggle is a full-width row now.
        assertFalse("use switchRow instead", sections.contains("CheckBox("))
        assertTrue(sections.contains("private fun Context.switchRow("))
    }

    @Test
    fun `every toggle explains itself`() {
        // The subtitle is not decoration: several of these decide whether the app moves windows
        // on its own, and the screen used to offer no way to tell one from another short of a
        // paragraph underneath the group.
        val calls = Regex("""switchRow\(\s*"([^"]+)",\s*"([^"]+)",""").findAll(sections).toList()
        assertTrue("expected every toggle to be a switchRow, found ${calls.size}", calls.size >= 4)
        calls.forEach { m ->
            assertTrue("'${m.groupValues[1]}' has no explanation", m.groupValues[2].length > 20)
        }
    }

    @Test
    fun `tappable things carry a touch minimum`() {
        val button = sections.substringAfter("private fun Context.button(").substringBefore("\n}")
        assertTrue("buttons must set a minimum height", button.contains("ConfigMetrics.TOUCH_DP"))
        assertTrue(
            "the split bar must be tall enough to hit",
            sections.contains("minimumHeight = activity.dp(ConfigMetrics.TOUCH_DP)"),
        )
    }

    @Test
    fun `the split has nudge buttons as well as a bar`() {
        // A SeekBar wants fine motor precision for a one-percent change, which is the wrong
        // demand to make of someone in a moving car.
        assertTrue(sections.contains("""step("−", -1)"""))
        assertTrue(sections.contains("""step("+", 1)"""))
        // They go through the bar, so persistence, the label and the announcement stay in one
        // place rather than three.
        val step = sections.substringAfter("fun step(label: String, by: Int)").substringBefore("\n            }")
        assertTrue(step.contains("slider.progress"))
    }
}
