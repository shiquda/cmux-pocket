package app.cmuxpocket.ui

/**
 * Gate for the first-launch guide.
 *
 * The guide is for a phone that has never reached a Gateway: a fresh install has no saved
 * profile, an upgraded install already carries one, and a device the user configured by hand
 * keeps its manual endpoint. Only the first case is shown the guide, and the persisted
 * [SettingsManager.onboardingCompleted] flag keeps it closed after the user pairs or skips.
 */
object FirstLaunchOnboarding {

    fun shouldShow(completed: Boolean, hasConfiguredEndpoint: Boolean): Boolean =
        !completed && !hasConfiguredEndpoint
}
