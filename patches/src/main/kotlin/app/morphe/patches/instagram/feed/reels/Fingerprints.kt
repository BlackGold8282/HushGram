/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.reels

import app.morphe.patcher.Fingerprint

/**
 * The parser that reads one home feed item from Instagram's JSON: a post or ad ("media_or_ad"), a
 * row of suggested reels ("clips_netego") or one of the other units. Its serializer holds the same
 * two keys but returns nothing; the parser answers the item as an Object.
 */
internal object FeedItemParserFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
    strings = listOf("media_or_ad", "clips_netego"),
)
