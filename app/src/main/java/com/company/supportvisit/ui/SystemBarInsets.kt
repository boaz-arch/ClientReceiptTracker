package com.company.supportvisit.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Android 15 (targetSdk 35) draws apps edge-to-edge. Keep content clear of the status bar,
 * navigation bar and display cutout by adding their size to the view's own padding.
 */
fun View.applySystemBarInsets() {
    val start = paddingStart
    val top = paddingTop
    val end = paddingEnd
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val isRtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        view.updatePadding(
            left = (if (isRtl) end else start) + bars.left,
            top = top + bars.top,
            right = (if (isRtl) start else end) + bars.right,
            bottom = bottom + bars.bottom
        )
        insets
    }
}
