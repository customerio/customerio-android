package io.customer.datapipelines.config

import io.customer.commontest.core.JUnit5Test
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class ScreenViewTest : JUnit5Test() {
    @Test
    fun getScreenView_givenNamesWithMatchingCase_expectCorrectScreenView() {
        val screenViewAnalytics = ScreenView.getScreenView("All")
        val screenViewInApp = ScreenView.getScreenView("InApp")

        screenViewAnalytics shouldBeEqualTo ScreenView.All
        screenViewInApp shouldBeEqualTo ScreenView.InApp
    }

    @Test
    fun getScreenView_givenNamesWithDifferentCase_expectCorrectScreenView() {
        val screenViewAnalytics = ScreenView.getScreenView("all")
        val screenViewInApp = ScreenView.getScreenView("inapp")

        screenViewAnalytics shouldBeEqualTo ScreenView.All
        screenViewInApp shouldBeEqualTo ScreenView.InApp
    }

    @Test
    fun getScreenView_givenInvalidValue_expectFallbackScreenView() {
        val parsedValue = ScreenView.getScreenView("none")

        parsedValue shouldBeEqualTo ScreenView.All
    }

    @Test
    fun getScreenView_givenEmptyValue_expectFallbackScreenView() {
        val parsedValue = ScreenView.getScreenView(screenView = "", fallback = ScreenView.InApp)

        parsedValue shouldBeEqualTo ScreenView.InApp
    }

    @Test
    fun getScreenView_givenNull_expectFallbackScreenView() {
        val parsedValue = ScreenView.getScreenView(null)

        parsedValue shouldBeEqualTo ScreenView.All
    }

    @Test
    fun toString_givenAllType() {
        ScreenView.All.toString() shouldBeEqualTo "ScreenView('all')"
    }

    @Test
    fun toString_givenInAppType() {
        ScreenView.InApp.toString() shouldBeEqualTo "ScreenView('inapp')"
    }
}
