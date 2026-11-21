package com.timeout.app.data

import android.annotation.SuppressLint
import android.content.SharedPreferences

class PreferencesDraftCheckpoint(private val preferences: SharedPreferences) : DraftCheckpointPersistence {
    override fun read(): String? = preferences.getString("checkpoint", null)

    // KTX edit(commit=true) discards the Boolean; a handoff must verify durable success.
    @SuppressLint("UseKtx")
    override fun write(value: String): Boolean = preferences.edit().putString("checkpoint", value).commit()
}
