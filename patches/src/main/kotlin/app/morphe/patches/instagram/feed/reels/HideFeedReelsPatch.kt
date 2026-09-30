/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.reels

import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide Reels in the feed"
internal const val FILTER = "$EXTENSION_PACKAGE/reels/FeedReels;->filter(Ljava/lang/Object;)Ljava/lang/Object;"

/** A kept type only the feed item holds a field of: the row of suggested reels. */
internal const val CLIPS_NETEGO = "Lcom/instagram/api/schemas/ClipsNetego;"

/** The feed item types FeedReels drops, which the item's type enum has to name. */
internal val REEL_UNITS = listOf("CLIPS_NETEGO", "IMMERSIVE_SEGUE_ITEM", "VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT")

@Suppress("unused")
val hideFeedReelsPatch = bytecodePatch(
    name = "Hide Reels in the feed",
    description = "Removes the rows of suggested reels between posts in your home feed, and the other units " +
        "that open the Reels viewer from there. A reel someone you follow posts stays.",
    default = false,
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        filterParsedFeedItems()
        enableStatus("feedReels")
    }
}

/**
 * Passes each feed item Instagram's static parse helper answers through the extension, which
 * answers null for a row of suggested reels and the like. Every caller of the helper on 449 skips a
 * null item, the way it skips one that didn't parse.
 */
internal fun BytecodePatchContext.filterParsedFeedItems() {
    val parser = uniqueMethod(PATCH, "feed item parser", FeedItemParserFingerprint)
    val itemTypes = parser.implementation!!.instructions
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .distinct()
        .filter { type -> classDefByOrNull(type)?.fields?.any { it.type == CLIPS_NETEGO } == true }
    val itemType = itemTypes.singleOrNull() ?: throw PatchException(
        "$PATCH: expected the feed item parser to make one class with a $CLIPS_NETEGO field, found $itemTypes",
    )
    requireOneReelsTypeField(itemType)

    val item = mutableClassDefBy(itemType)
    val helpers = item.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == itemType && method.parameterTypes.size == 1 &&
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "parseFromJsonParser"
            } == true
    }
    val helper = helpers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static method of $itemType that parses one from JSON, found ${helpers.size}",
    )
    val returns = helper.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) throw PatchException("$PATCH: ${helper.definingClass}->${helper.name} returns no object")
    returns.asReversed().forEach { (index, register) ->
        // At the return's own label, so a branch straight to the return passes through the filter.
        helper.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { v$register }, $FILTER
                move-result-object v$register
                check-cast v$register, $itemType
            """,
        )
    }
}

/**
 * The extension finds the item's type by reading each of its enum fields and comparing the
 * constant's name with [REEL_UNITS]. That holds only while one of the item's enum types names them,
 * and none of the others names any; fail here when an update changes it.
 */
private fun BytecodePatchContext.requireOneReelsTypeField(itemType: String) {
    val enumFields = classDefBy(itemType).fields
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
        .mapNotNull { field -> classDefByOrNull(field.type)?.takeIf { it.superclass == "Ljava/lang/Enum;" } }
    val naming = enumFields.map { enum ->
        val names = enum.methods.filter { it.name == "<clinit>" }.flatMap { method ->
            method.implementation?.instructions?.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
            }.orEmpty()
        }.toSet()
        enum.type to REEL_UNITS.filter { it in names }
    }
    val holders = naming.filter { it.second.isNotEmpty() }
    if (holders.size != 1 || holders.single().second != REEL_UNITS) {
        throw PatchException(
            "$PATCH: expected one enum field of $itemType whose type names all of $REEL_UNITS, found $naming",
        )
    }
}
