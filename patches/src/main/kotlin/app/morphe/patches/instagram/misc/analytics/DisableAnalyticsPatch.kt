/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterEveryReturn
import app.morphe.patches.instagram.misc.extension.filterEveryStringLoad
import app.morphe.patches.instagram.misc.extension.handleTargets
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Disable analytics"

private const val ENDPOINT = "$EXTENSION_PACKAGE/misc/Analytics;->endpoint(Ljava/lang/String;)Ljava/lang/String;"
@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = "Disable analytics",
    description = "Sends Instagram's usage events to an address on your phone that refuses them, instead " +
        "of to Instagram's and Facebook's logging servers. Restart Instagram after changing the switch.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("disableAnalytics")

        handleTargets(PATCH, "event upload addresses", listOf("builder", "graph", "mqtt", "reports", "pings")) { target ->
            when (target) {
                // Instagram's own logging_client_events and pigeon_nest addresses, built from a host.
                "builder" -> AnalyticsEndpointFingerprint.matchAllOrNull().orEmpty().let { matches ->
                    when (matches.size) {
                        1 -> matches.single().method.filterEveryReturn(PATCH, ENDPOINT).let { null }
                        0 -> "no method builds the logging_client_events and pigeon_nest address"
                        else -> "${matches.size} methods build the logging_client_events address, expected one"
                    }
                }
                // The address the MQTT client posts its analytics to, which the server can set in the
                // client's settings. Its fallback is the Graph address, which "graph" covers already.
                "mqtt" -> wrapMqttAnalyticsEndpoint(ENDPOINT)
                // Lacrima's crash and reliability reports, to an address it builds on
                // b-www.facebook.com before the settings are ready and reads again for each send.
                "reports" -> ReportAddressFingerprint.matchAllOrNull().orEmpty().let { matches ->
                    when {
                        matches.size > 1 -> "${matches.size} methods build the b-www.facebook.com report address, expected one"
                        matches.isEmpty() -> "no method builds the b-www.facebook.com report address"
                        filterReportAddressReads(matches.single().method, ENDPOINT) == 0 -> "nothing reads the b-www.facebook.com report address it builds"
                        else -> null
                    }
                }
                // Lacrima's startup and debug pings, as a constant in each sender.
                "pings" -> if (filterEveryStringLoad(ERROR_PING_ENDPOINT, ENDPOINT) > 0) {
                    null
                } else {
                    "no code loads $ERROR_PING_ENDPOINT"
                }
                // The same events to Facebook's Graph API, as a constant wherever Instagram names it.
                else -> if (filterEveryStringLoad(GRAPH_LOGGING_ENDPOINT, ENDPOINT) > 0) {
                    null
                } else {
                    "no code loads $GRAPH_LOGGING_ENDPOINT"
                }
            }
        }

        enableStatus("disableAnalytics")
    }
}
