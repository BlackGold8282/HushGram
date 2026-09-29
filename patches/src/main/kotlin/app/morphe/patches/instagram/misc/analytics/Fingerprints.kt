/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * The method that builds the address Instagram uploads its usage events to, from a host and a
 * flag: "https://" + host + "/logging_client_events", or "/pigeon_nest" for Instagram's own
 * pipeline. Its class and name are Redex names, so the fingerprint uses its shape and strings.
 */
internal object AnalyticsEndpointFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", "Z"),
    strings = listOf("https://", "/pigeon_nest", "/logging_client_events"),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** The address Instagram also sends usage events to, Facebook's Graph API logging endpoint. */
internal const val GRAPH_LOGGING_ENDPOINT = "https://graph.facebook.com/logging_client_events"
