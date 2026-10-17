package com.railspotting.android.store

import org.json.JSONObject

/** The link result (contract v1.7, 1.4) the app keeps: who is signed in and which receiver. */
data class Linked(
    val deviceId: String,
    val receiverName: String,
    val commissioning: String,
    val email: String,
    val accountName: String,
    val relinked: Boolean,
) {
    val provisional: Boolean get() = commissioning != "live"

    fun toJson(): String = JSONObject()
        .put("device_id", deviceId)
        .put("receiver_name", receiverName)
        .put("commissioning", commissioning)
        .put("account", JSONObject().put("email", email).put("name", accountName))
        .put("relinked", relinked)
        .toString()

    companion object {
        /** From the headend's link result; null without a device ID. */
        fun fromResult(o: JSONObject): Linked? {
            val id = o.optString("device_id")
            if (id.isEmpty()) return null
            val acc = o.optJSONObject("account") ?: JSONObject()
            return Linked(
                deviceId = id,
                receiverName = o.optString("receiver_name"),
                commissioning = o.optString("commissioning", "provisional"),
                email = acc.optString("email"),
                accountName = acc.optString("name"),
                relinked = o.optBoolean("relinked"),
            )
        }

        fun fromJson(s: String?): Linked? = s?.let {
            try {
                fromResult(JSONObject(it))
            } catch (_: org.json.JSONException) {
                null
            }
        }
    }
}
