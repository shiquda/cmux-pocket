package app.cmuxpocket.ui

/**
 * Decides which channel alerts the user about a finished agent turn.
 *
 * Only the ordinary full-screen foreground keeps the in-app feedback. A
 * backgrounded, picture-in-picture or multi-window app stays STARTED while the
 * user is looking at something else, so those take the system notification.
 */
fun usesInAppCompletionFeedback(
    isStarted: Boolean,
    isInPictureInPicture: Boolean,
    isInMultiWindow: Boolean,
): Boolean = isStarted && !isInPictureInPicture && !isInMultiWindow
