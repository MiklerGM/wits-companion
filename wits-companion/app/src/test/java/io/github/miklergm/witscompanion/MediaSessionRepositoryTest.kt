package io.github.miklergm.witscompanion

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.github.miklergm.witscompanion.media.MediaSessionRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for the media panel being dead: the repository was never started, so its
 * snapshot stayed at the default "not granted" forever. [MediaSessionRepository.ensureObserving]
 * must (re)evaluate permission on demand — notification access can be granted *after* the
 * process starts — and be safe to call repeatedly.
 */
@RunWith(RobolectricTestRunner::class)
class MediaSessionRepositoryTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun grantListener() {
        Settings.Secure.putString(
            ctx.contentResolver,
            "enabled_notification_listeners",
            "${ctx.packageName}/.media.WitsNotificationListenerService",
        )
    }

    @Test
    fun `a fresh repository reports no permission`() {
        val repo = MediaSessionRepository(ctx)
        assertFalse(repo.snapshot.permissionGranted)
        assertFalse(repo.isPermissionGranted())
    }

    @Test
    fun `permission is picked up from the setting`() {
        val repo = MediaSessionRepository(ctx)
        grantListener()
        assertTrue("the granted listener must be recognised", repo.isPermissionGranted())
    }

    @Test
    fun `ensureObserving reflects a grant made after start, without a restart`() {
        val repo = MediaSessionRepository(ctx)
        repo.start()                       // started before the grant, like the real app
        assertFalse(repo.snapshot.permissionGranted)

        grantListener()                    // access granted later (self-grant / adb)
        repo.ensureObserving()             // what a media screen calls on open

        assertTrue(
            "opening the panel after a grant must surface it",
            repo.snapshot.permissionGranted,
        )
    }

    @Test
    fun `ensureObserving is idempotent`() {
        val repo = MediaSessionRepository(ctx)
        grantListener()
        // Several opens in a row must not throw or double-register.
        repeat(3) { repo.ensureObserving() }
        assertTrue(repo.snapshot.permissionGranted)
    }
}
