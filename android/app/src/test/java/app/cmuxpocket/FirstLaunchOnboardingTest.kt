package app.cmuxpocket

import app.cmuxpocket.ui.FirstLaunchOnboarding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLaunchOnboardingTest {

    @Test
    fun `fresh install sees the guide`() {
        assertTrue(FirstLaunchOnboarding.shouldShow(completed = false, hasConfiguredEndpoint = false))
    }

    @Test
    fun `pairing or skipping closes the guide for later launches`() {
        assertFalse(FirstLaunchOnboarding.shouldShow(completed = true, hasConfiguredEndpoint = false))
    }

    @Test
    fun `an install with a saved or typed endpoint is never intercepted`() {
        // Upgrades carry a profile, and a hand-configured endpoint is already paired in spirit.
        assertFalse(FirstLaunchOnboarding.shouldShow(completed = false, hasConfiguredEndpoint = true))
    }
}
