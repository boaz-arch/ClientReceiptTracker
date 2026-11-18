package com.company.supportvisit.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.company.supportvisit.R
import com.google.android.material.appbar.MaterialToolbar

/**
 * Puts a top bar with a back arrow above this screen's content. The arrow does the same as the
 * phone's Back button (so unsaved-changes checks still run). Call after setContentView.
 *
 * The bar takes the status-bar inset; the content below it gets the remaining insets, so the
 * screen's own [applySystemBarInsets] keeps working for the sides and bottom.
 */
fun AppCompatActivity.setUpBackBar(@StringRes title: Int? = null) {
    val content = findViewById<ViewGroup>(android.R.id.content)
    val screen = content.getChildAt(0) ?: return
    content.removeView(screen)

    val toolbar = MaterialToolbar(this).apply {
        setNavigationIcon(R.drawable.ic_arrow_back)
        setNavigationContentDescription(R.string.nav_back)
        setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        if (title != null) setTitle(title)
    }
    val bar = FrameLayout(this).apply {
        addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(screen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    content.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    ViewCompat.setOnApplyWindowInsetsListener(column) { _, insets ->
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val all = insets.getInsets(types)
        bar.updatePadding(left = all.left, top = all.top, right = all.right)
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val below = WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(bars.left, 0, bars.right, bars.bottom))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(cutout.left, 0, cutout.right, cutout.bottom))
            .build()
        ViewCompat.dispatchApplyWindowInsets(screen, below)
        WindowInsetsCompat.CONSUMED
    }
}
