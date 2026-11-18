package com.company.supportvisit.shortcuts

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.company.supportvisit.MainActivity
import com.company.supportvisit.NewVisitActivity
import com.company.supportvisit.PastVisitsActivity
import com.company.supportvisit.R
import com.company.supportvisit.TasksActivity

/**
 * Long-press the app icon: New visit report, Past visits, Tasks. Made in code (not
 * res/xml/shortcuts.xml) so they follow the app's language and its final application id.
 * Each opens on top of the home screen, so Back returns there.
 */
object Shortcuts {

    fun publish(context: Context) {
        val shortcuts = listOf(
            shortcut(context, "new_visit", R.string.shortcut_new_visit, R.drawable.ic_shortcut_add, NewVisitActivity::class.java),
            shortcut(context, "past_visits", R.string.shortcut_past_visits, R.drawable.ic_shortcut_list, PastVisitsActivity::class.java),
            shortcut(context, "tasks", R.string.shortcut_tasks, R.drawable.ic_shortcut_check, TasksActivity::class.java)
        )
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            // Some launchers limit or refuse shortcuts; the app works the same without them.
        }
    }

    private fun shortcut(
        context: Context,
        id: String,
        @StringRes label: Int,
        @DrawableRes icon: Int,
        target: Class<*>
    ): ShortcutInfoCompat {
        val home = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        val open = Intent(context, target).setAction(Intent.ACTION_VIEW)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntents(arrayOf(home, open))
            .build()
    }
}
