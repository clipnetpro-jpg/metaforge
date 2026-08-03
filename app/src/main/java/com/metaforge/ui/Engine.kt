package com.metaforge.ui

import android.content.Context
import com.metaforge.app.BuildConfig
import com.metaforge.data.MediaAccess
import com.metaforge.engine.ExifTool

/** One place for the screens to reach the engine, so no screen owns it. */
object Engine {

    fun exifTool(context: Context): ExifTool? =
        ExifTool.get(context.applicationContext, BuildConfig.VERSION_NAME)

    fun media(context: Context): MediaAccess = MediaAccess(context.applicationContext)

    /** Human explanation for why the engine is not available, if it is not. */
    fun failure(): String = ExifTool.lastStartupDiagnostics
}
