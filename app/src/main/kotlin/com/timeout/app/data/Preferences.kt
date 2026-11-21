package com.timeout.app.data

import android.annotation.SuppressLint
import android.content.SharedPreferences

/** KTX edit(commit = true) returns Unit. These writes must detect failed durability. */
@SuppressLint("UseKtx")
fun SharedPreferences.commitOrThrow(message: String, update: SharedPreferences.Editor.() -> Unit) {
    check(edit().apply(update).commit()) { message }
}
