package hu.billman.konsolessh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hu.billman.konsolessh.ui.swipeToDismissHorizontally
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeToDismissTest {

    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0

    private fun show() = compose.setContent {
        Box(
            modifier = Modifier
                .size(300.dp, 400.dp)
                .testTag(TAG)
                .swipeToDismissHorizontally { dismissed++ },
        )
    }

    @Test
    fun aLongSwipeEitherWayDismisses() {
        show()
        compose.onNodeWithTag(TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun aLongSwipeToTheRightDismissesToo() {
        show()
        compose.onNodeWithTag(TAG).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun aShortSlowSwipeSpringsBack() {
        show()
        compose.onNodeWithTag(TAG).performTouchInput {
            swipeRight(startX = centerX, endX = centerX + width * 0.1f, durationMillis = 800)
        }
        compose.waitForIdle()
        assertEquals(0, dismissed)
    }

    @Test
    fun aVerticalSwipeDoesNotDismiss() {
        show()
        compose.onNodeWithTag(TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, dismissed)
    }

    private companion object {
        const val TAG = "swipe"
    }
}
