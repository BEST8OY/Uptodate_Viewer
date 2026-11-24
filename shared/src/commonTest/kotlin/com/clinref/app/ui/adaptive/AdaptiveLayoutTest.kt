package com.clinref.app.ui.adaptive

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveLayoutTest {

    @Test
    fun testWindowWidthClassBreakpoints() {
        assertEquals(WindowWidthClass.COMPACT, calculateWindowWidthClass(0.dp))
        assertEquals(WindowWidthClass.COMPACT, calculateWindowWidthClass(320.dp))
        assertEquals(WindowWidthClass.COMPACT, calculateWindowWidthClass(599.dp))
        assertEquals(WindowWidthClass.MEDIUM, calculateWindowWidthClass(600.dp))
        assertEquals(WindowWidthClass.MEDIUM, calculateWindowWidthClass(839.dp))
        assertEquals(WindowWidthClass.EXPANDED, calculateWindowWidthClass(840.dp))
        assertEquals(WindowWidthClass.EXPANDED, calculateWindowWidthClass(1280.dp))
    }

    @Test
    fun testWindowHeightClassBreakpoints() {
        assertEquals(WindowHeightClass.COMPACT, calculateWindowHeightClass(0.dp))
        assertEquals(WindowHeightClass.COMPACT, calculateWindowHeightClass(360.dp))
        assertEquals(WindowHeightClass.COMPACT, calculateWindowHeightClass(479.dp))
        assertEquals(WindowHeightClass.MEDIUM, calculateWindowHeightClass(480.dp))
        assertEquals(WindowHeightClass.MEDIUM, calculateWindowHeightClass(899.dp))
        assertEquals(WindowHeightClass.EXPANDED, calculateWindowHeightClass(900.dp))
        assertEquals(WindowHeightClass.EXPANDED, calculateWindowHeightClass(1200.dp))
    }

    @Test
    fun testWindowAdaptiveInfoProperties() {
        // Phone Portrait
        val phonePortrait = calculateWindowAdaptiveInfo(360.dp, 800.dp)
        assertEquals(WindowWidthClass.COMPACT, phonePortrait.widthClass)
        assertEquals(WindowHeightClass.MEDIUM, phonePortrait.heightClass)
        assertFalse(phonePortrait.useNavigationRail)
        assertFalse(phonePortrait.isCompactHeight)
        assertFalse(phonePortrait.supportsSupportingPane)
        assertEquals(16.dp, phonePortrait.horizontalMargin)

        // Phone Landscape
        val phoneLandscape = calculateWindowAdaptiveInfo(800.dp, 360.dp)
        assertEquals(WindowWidthClass.MEDIUM, phoneLandscape.widthClass)
        assertEquals(WindowHeightClass.COMPACT, phoneLandscape.heightClass)
        assertTrue(phoneLandscape.useNavigationRail)
        assertTrue(phoneLandscape.isCompactHeight)
        assertFalse(phoneLandscape.supportsSupportingPane)
        assertEquals(24.dp, phoneLandscape.horizontalMargin)

        // Large Desktop / Tablet Wide
        val desktopWide = calculateWindowAdaptiveInfo(1440.dp, 900.dp)
        assertEquals(WindowWidthClass.EXPANDED, desktopWide.widthClass)
        assertEquals(WindowHeightClass.EXPANDED, desktopWide.heightClass)
        assertTrue(desktopWide.useNavigationRail)
        assertFalse(desktopWide.isCompactHeight)
        assertTrue(desktopWide.supportsSupportingPane)
        assertEquals(24.dp, desktopWide.horizontalMargin)
    }

    @Test
    fun testSupportingPaneBoundary() {
        val belowThreshold = calculateWindowAdaptiveInfo(1039.dp, 800.dp)
        assertFalse(belowThreshold.supportsSupportingPane)

        val atThreshold = calculateWindowAdaptiveInfo(1040.dp, 800.dp)
        assertTrue(atThreshold.supportsSupportingPane)

        val aboveThreshold = calculateWindowAdaptiveInfo(1200.dp, 800.dp)
        assertTrue(aboveThreshold.supportsSupportingPane)
    }

    @Test
    fun testNavigationRailAndMarginBoundaries() {
        val compactEdge = calculateWindowAdaptiveInfo(599.dp, 600.dp)
        assertFalse(compactEdge.useNavigationRail)
        assertEquals(16.dp, compactEdge.horizontalMargin)

        val mediumEdge = calculateWindowAdaptiveInfo(600.dp, 600.dp)
        assertTrue(mediumEdge.useNavigationRail)
        assertEquals(24.dp, mediumEdge.horizontalMargin)

        val expandedEdge = calculateWindowAdaptiveInfo(840.dp, 600.dp)
        assertTrue(expandedEdge.useNavigationRail)
        assertEquals(24.dp, expandedEdge.horizontalMargin)
    }

    @Test
    fun testPaneAdaptiveInfoPreservesWindowChromeDecisions() {
        val windowInfo = calculateWindowAdaptiveInfo(1040.dp, 900.dp)
        assertTrue(windowInfo.supportsSupportingPane)
        assertTrue(windowInfo.useNavigationRail)

        val postRailPane = calculatePaneAdaptiveInfo(
            paneWidthDp = 960.dp,
            paneHeightDp = 900.dp,
            windowWidthDp = windowInfo.windowWidthDp,
            windowHeightDp = windowInfo.windowHeightDp
        )
        assertEquals(WindowWidthClass.EXPANDED, postRailPane.widthClass)
        assertTrue(postRailPane.supportsSupportingPane)
        assertTrue(postRailPane.useNavigationRail)

        val chatSplitPane = calculatePaneAdaptiveInfo(
            paneWidthDp = 460.dp,
            paneHeightDp = 900.dp,
            windowWidthDp = postRailPane.windowWidthDp,
            windowHeightDp = postRailPane.windowHeightDp
        )
        assertEquals(WindowWidthClass.COMPACT, chatSplitPane.widthClass)
        assertTrue(chatSplitPane.isCompact)
        assertEquals(16.dp, chatSplitPane.horizontalMargin)
        assertTrue(chatSplitPane.supportsSupportingPane)
        assertTrue(chatSplitPane.useNavigationRail)
    }

    @Test
    fun testShowInlineRowActionsMatchesPlatformOrExpandedWidth() {
        val compact = calculateWindowAdaptiveInfo(400.dp, 800.dp)
        assertEquals(compact.isExpanded || isDesktopPlatform(), compact.showInlineRowActions)

        val expanded = calculateWindowAdaptiveInfo(900.dp, 800.dp)
        assertTrue(expanded.showInlineRowActions)
    }
}
