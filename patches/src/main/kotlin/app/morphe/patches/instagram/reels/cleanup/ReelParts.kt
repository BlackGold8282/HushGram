/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE

private const val DECLUTTER = "$EXTENSION_PACKAGE/reels/ReelDeclutter;"
internal const val HIDE_FOLLOW_BUTTON = "$DECLUTTER->hideFollowButton()Z"
internal const val HIDE_CHIPS = "$DECLUTTER->hideChips()Z"
internal const val HIDE_SOCIAL_FOOTER = "$DECLUTTER->hideSocialFooter()Z"

/**
 * A part of the Reels viewer the patch hides: the method whose marker ends in [marker], and the
 * extension hook asked first thing in it. A render answers nothing when the hook says to hide, and
 * the check answers no.
 */
internal data class ReelPart(val marker: String, val hook: String, val check: Boolean = false)

/** The Follow button beside a reel's author. The suggested accounts' cards draw a Legacy one, which stays. */
internal val FOLLOW_PARTS = listOf(ReelPart("ClipsFollowButtonComponent_render", HIDE_FOLLOW_BUTTON))

/**
 * The pills that prompt you to make something or promote something. On Instagram 449 the first
 * four and the glasses pills sit above the author, built by the component that also draws a live
 * badge and a state-controlled media label, which have renders of their own and stay. The Meta AI
 * pill sits in the reel's media info.
 */
internal val CHIP_PARTS = listOf(
    "ClipsBaselAttributionPillComponent_render",
    "ClipsBaselCreativeToolAttributionPillComponent_render",
    "ClipsBaselPlatformizedCreativeToolAttributionComponent_render",
    "StoriesTemplatePillComponent_render",
    "ClipsMetaAiPillComponent_render",
    "MetaAffiliateCTAComponent_render",
    "ClipsWearablesAttributionBlackToGradientPillComponent_render",
    "ClipsWearablesAttributionBluePillComponent_render",
    "ClipsWearablesAttributionRimLightInnerPillComponent_render",
    "ClipsWearablesAttributionSemiTransparentPillComponent_render",
    "ClipsWearablesAttributionShopAIGlassesSemiTransparentPillComponent_render",
    "ClipsWearablesAttributionShopRBMGlassesBlackPillComponent_render",
    "ClipsWearablesAttributionShopRBMGlassesSemiTransparentPillComponent_render",
).map { ReelPart(it, HIDE_CHIPS) }

/**
 * Friends' activity and the comment preview. The bubbles of friends' likes, comments and follows
 * go above the author only when this check answers yes, and the media info draws the other two.
 */
internal val SOCIAL_PARTS = listOf(
    ReelPart("ClipsInfoOverlayUseCase_shouldShowFloatingBubblesAboveUsername", HIDE_SOCIAL_FOOTER, check = true),
    ReelPart("ClipsInlineCommentSocialContextComponent_render", HIDE_SOCIAL_FOOTER),
    ReelPart("ClipsFriendlyViewerComponent_render", HIDE_SOCIAL_FOOTER),
)

internal val REEL_PARTS = FOLLOW_PARTS + CHIP_PARTS + SOCIAL_PARTS
