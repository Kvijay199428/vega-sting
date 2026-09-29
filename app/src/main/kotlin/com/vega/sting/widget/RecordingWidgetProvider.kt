package com.vega.sting.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import com.vega.sting.R
import com.vega.sting.services.RecordingService

class RecordingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        try {
            for (widgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, widgetId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            super.onReceive(context, intent)
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                refreshWidget(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {

        fun refreshWidget(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, RecordingWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)

                for (id in ids) {
                    updateWidget(context, manager, id)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_recording)

                val model = when (WidgetStateManager.getState(context)) {
                    WidgetState.IDLE -> WidgetModel(
                        title = "VEGA STING",
                        titleColor = TEXT,
                        leftText = "REC VIDEO",
                        leftColor = ACCENT,
                        rightText = "REC AUDIO",
                        rightColor = ACCENT,
                        leftAction = RecordingService.ACTION_START_VIDEO,
                        rightAction = RecordingService.ACTION_START_AUDIO
                    )
                    WidgetState.RECORDING_VIDEO -> WidgetModel(
                        title = "\u25CF VEGA STING",
                        titleColor = ERROR,
                        leftText = "STOP",
                        leftColor = ERROR,
                        rightText = "AUDIO",
                        rightColor = ACCENT,
                        leftAction = RecordingService.ACTION_STOP,
                        rightAction = RecordingService.ACTION_START_AUDIO
                    )
                    WidgetState.RECORDING_AUDIO -> WidgetModel(
                        title = "\u25CF VEGA STING",
                        titleColor = ACCENT,
                        leftText = "VIDEO",
                        leftColor = ACCENT,
                        rightText = "STOP",
                        rightColor = ERROR,
                        leftAction = RecordingService.ACTION_START_VIDEO,
                        rightAction = RecordingService.ACTION_STOP
                    )
                }
                val sizing = getWidgetSizing(manager, widgetId)

                views.setTextViewText(R.id.widgetTitle, model.title)
                views.setTextViewText(R.id.btnLeft, model.leftText)
                views.setTextViewText(R.id.btnRight, model.rightText)
                views.setTextColor(R.id.widgetTitle, model.titleColor)
                views.setTextColor(R.id.btnLeft, model.leftColor)
                views.setTextColor(R.id.btnRight, model.rightColor)
                views.setTextViewTextSize(R.id.widgetTitle, TypedValue.COMPLEX_UNIT_SP, sizing.titleSp)
                views.setTextViewTextSize(R.id.btnLeft, TypedValue.COMPLEX_UNIT_SP, sizing.buttonSp)
                views.setTextViewTextSize(R.id.btnRight, TypedValue.COMPLEX_UNIT_SP, sizing.buttonSp)
                views.setViewPadding(
                    R.id.widgetRoot,
                    dp(context, sizing.rootPaddingDp),
                    dp(context, sizing.rootPaddingDp),
                    dp(context, sizing.rootPaddingDp),
                    dp(context, sizing.rootPaddingDp)
                )
                views.setViewPadding(
                    R.id.buttonPanel,
                    dp(context, sizing.panelPaddingDp),
                    dp(context, sizing.panelPaddingDp),
                    dp(context, sizing.panelPaddingDp),
                    dp(context, sizing.panelPaddingDp)
                )

                views.setOnClickPendingIntent(
                    R.id.btnLeft,
                    buildServicePendingIntent(context, model.leftAction, 101)
                )

                views.setOnClickPendingIntent(
                    R.id.btnRight,
                    buildServicePendingIntent(context, model.rightAction, 102)
                )

                manager.updateAppWidget(widgetId, views)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun getWidgetSizing(
            manager: AppWidgetManager,
            widgetId: Int
        ): WidgetSizing {
            val options = manager.getAppWidgetOptions(widgetId)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 220)
            val longSide = maxOf(width, height).toFloat()
            val shortSide = minOf(width, height).coerceAtLeast(1).toFloat()
            val ratio = longSide / shortSide

            return when {
                ratio > 1.35f -> WidgetSizing(
                    rootPaddingDp = 14,
                    panelPaddingDp = 8,
                    titleSp = 18f,
                    buttonSp = 11f
                )
                shortSide < 230f -> WidgetSizing(
                    rootPaddingDp = 16,
                    panelPaddingDp = 8,
                    titleSp = 19f,
                    buttonSp = 11f
                )
                else -> WidgetSizing(
                    rootPaddingDp = 18,
                    panelPaddingDp = 10,
                    titleSp = 20f,
                    buttonSp = 12f
                )
            }
        }

        private fun dp(context: Context, value: Int): Int {
            return (value * context.resources.displayMetrics.density).toInt()
        }

        private fun buildServicePendingIntent(
            context: Context,
            action: String,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(context, RecordingService::class.java).apply {
                this.action = action
                setPackage(context.packageName)
            }
            return PendingIntent.getForegroundService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private val TEXT = 0xFFFFFFFF.toInt()
        private val ACCENT = 0xFFFF8800.toInt()
        private val ERROR = 0xFFFF3300.toInt()
    }
}

private data class WidgetModel(
    val title: String,
    val titleColor: Int,
    val leftText: String,
    val leftColor: Int,
    val rightText: String,
    val rightColor: Int,
    val leftAction: String,
    val rightAction: String
)

private data class WidgetSizing(
    val rootPaddingDp: Int,
    val panelPaddingDp: Int,
    val titleSp: Float,
    val buttonSp: Float
)
