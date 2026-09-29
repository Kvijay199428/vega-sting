package com.vega.sting.widget

import android.content.Context

enum class WidgetState {
    IDLE,
    RECORDING_VIDEO,
    RECORDING_AUDIO
}

object WidgetStateManager {

    private const val PREFS = "widget_state"
    private const val KEY_STATE = "state"

    fun saveState(
        context: Context,
        state: String
    ) {
        saveState(context, parseState(state))
    }

    fun saveState(
        context: Context,
        state: WidgetState
    ) {

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(KEY_STATE, state.name)
            .commit()
    }

    fun getState(
        context: Context
    ): WidgetState {

        val savedState = context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .getString(
                KEY_STATE,
                "IDLE"
            ) ?: "IDLE"

        return parseState(savedState)
    }

    private fun parseState(state: String): WidgetState {
        return when (state) {
            WidgetState.RECORDING_VIDEO.name -> WidgetState.RECORDING_VIDEO
            WidgetState.RECORDING_AUDIO.name -> WidgetState.RECORDING_AUDIO
            else -> WidgetState.IDLE
        }
    }
}
