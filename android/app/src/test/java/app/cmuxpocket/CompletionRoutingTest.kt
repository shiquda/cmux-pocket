package app.cmuxpocket

import app.cmuxpocket.ui.usesInAppCompletionFeedback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A completion must alert exactly once: in-app only in the ordinary foreground,
 * through the system notification everywhere else.
 */
class CompletionRoutingTest {

    @Test
    fun fullScreenForegroundUsesInAppFeedback() {
        assertTrue(
            usesInAppCompletionFeedback(
                isStarted = true,
                isInPictureInPicture = false,
                isInMultiWindow = false,
            )
        )
    }

    @Test
    fun backgroundUsesSystemNotification() {
        assertFalse(
            usesInAppCompletionFeedback(
                isStarted = false,
                isInPictureInPicture = false,
                isInMultiWindow = false,
            )
        )
    }

    @Test
    fun pictureInPictureUsesSystemNotification() {
        assertFalse(
            usesInAppCompletionFeedback(
                isStarted = true,
                isInPictureInPicture = true,
                isInMultiWindow = false,
            )
        )
    }

    @Test
    fun multiWindowUsesSystemNotification() {
        assertFalse(
            usesInAppCompletionFeedback(
                isStarted = true,
                isInPictureInPicture = false,
                isInMultiWindow = true,
            )
        )
    }

    @Test
    fun pictureInPictureInsideMultiWindowUsesSystemNotification() {
        assertFalse(
            usesInAppCompletionFeedback(
                isStarted = true,
                isInPictureInPicture = true,
                isInMultiWindow = true,
            )
        )
    }
}
