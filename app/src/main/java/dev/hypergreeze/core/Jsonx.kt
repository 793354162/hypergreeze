package dev.hypergreeze.core

import org.json.JSONArray
import org.json.JSONObject

/** Small `org.json` helpers that never throw and distinguish "absent" from "false"/"0". */

internal fun JSONObject.oStr(key: String): String? =
    if (has(key) && !isNull(key)) optString(key, "") else null

internal fun JSONObject.oBool(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

internal fun JSONObject.oLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

internal fun JSONObject.oInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

internal fun JSONObject.oObj(key: String): JSONObject? = optJSONObject(key)

internal fun JSONObject.oArr(key: String): JSONArray? = optJSONArray(key)

internal fun JSONArray.strings(): List<String> = buildList {
    for (i in 0 until length()) {
        val v = optString(i, "")
        if (v.isNotEmpty()) add(v)
    }
}

internal fun JSONArray.objects(): List<JSONObject> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it) }
    }
}

/** Puts [value] only when it is non-null, keeping the document compact. */
internal fun JSONObject.putOpt(key: String, value: Any?): JSONObject {
    if (value != null) put(key, value)
    return this
}
