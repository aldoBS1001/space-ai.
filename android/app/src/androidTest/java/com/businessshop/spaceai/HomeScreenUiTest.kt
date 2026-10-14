package com.businessshop.spaceai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.businessshop.spaceai.core.StorageSnapshot
import com.businessshop.spaceai.data.ScanPhase
import com.businessshop.spaceai.data.ScanProgress
import com.businessshop.spaceai.ui.UiState
import com.businessshop.spaceai.ui.screens.HomeScreen
import com.businessshop.spaceai.ui.theme.SpaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class HomeScreenUiTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun analyzeButtonTriggersScan() {
        var clicks = 0
        rule.setContent {
            SpaceTheme { HomeScreen(UiState(storage = StorageSnapshot(128_000_000_000, 40_000_000_000, 0)), { clicks++ }, {}, {}, {}) }
        }
        rule.onNodeWithText(ctx.getString(R.string.analyze).uppercase(Locale.getDefault())).assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test fun progressShowsRealCountsAndCancel() {
        var cancelled = false
        rule.setContent {
            SpaceTheme { HomeScreen(UiState(progress = ScanProgress(ScanPhase.PHOTOS, 25, 100)), {}, { cancelled = true }, {}, {}) }
        }
        rule.onNodeWithText(ctx.getString(R.string.progress_count, 25, 100), substring = true).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.cancel)).performClick()
        assertEquals(true, cancelled)
    }
}
