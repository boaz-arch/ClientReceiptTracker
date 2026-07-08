package com.company.supportvisit.signature

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

object SignatureManager {

    fun saveSignature(

        context: Context,
        bitmap: Bitmap,
        visitId: String

    ): File {

        val file = File(

            context.filesDir,

            "${visitId}_signature.png"
        )

        FileOutputStream(file).use {

            bitmap.compress(

                Bitmap.CompressFormat.PNG,

                100,

                it
            )
        }

        return file
    }
}