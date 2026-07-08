package com.company.supportvisit.utils

import android.app.Dialog
import android.content.Context
import android.widget.TextView
import com.company.supportvisit.R

class LoadingDialog(
    context: Context
) {

    private val dialog =
        Dialog(context)

    init {

        dialog.setContentView(
            R.layout.dialog_loading
        )

        dialog.setCancelable(false)
    }

    fun show(
        message: String
    ) {

        val text =
            dialog.findViewById<TextView>(
                R.id.tvLoading
            )

        text.text = message

        dialog.show()
    }

    fun dismiss() {

        dialog.dismiss()
    }
}