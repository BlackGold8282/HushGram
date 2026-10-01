/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.following

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.answerFlagReads
import app.morphe.patches.instagram.misc.flags.findFlagReads
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Start Home on Following"
internal const val FEED_FLAG = "$EXTENSION_PACKAGE/feed/FollowingFeed;->flag(I)Z"
internal const val SAVED_FEED = "$EXTENSION_PACKAGE/feed/FollowingFeed;->saved(Ljava/lang/String;)Ljava/lang/String;"

/** The preference Instagram keeps the feed you last picked from Home's feed picker in. */
internal const val SAVED_FEED_KEY = "last_selected_feed_type"

/** The server flag that has Instagram remember the feed you pick. On 449 it's read twelve times. */
internal const val REMEMBERED_FEED_FLAG = 0x810e7900064f82L

/**
 * The server flag that puts For you first in Home's feed picker and the picked feed's name at the
 * top of Home. On 449 it's read three times. Without it the picker has no For you, since Home
 * stands for it, and Home's top shows only the picker's arrow.
 */
internal const val FOR_YOU_PICKER_FLAG = 0x810e7900004f7cL

/** The flags the patch answers on: together they're Instagram's own For you and Following picker. */
internal val FEED_PICKER_FLAGS = listOf(REMEMBERED_FEED_FLAG, FOR_YOU_PICKER_FLAG)

/**
 * Opens Home on the Following feed. Off in the default selection: which feed Home starts on is the
 * user's pick, and For you stays one tap away at the top of Home.
 */
@Suppress("unused")
val startOnFollowingPatch = bytecodePatch(
    name = "Start Home on Following",
    description = "Opens Home on posts from accounts you follow. Tap Following at the top to switch to For you, and " +
        "Home remembers your pick. A change to the switch shows once Instagram restarts.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("followingFeed")
        val reads = findFlagReads(PATCH, FEED_PICKER_FLAGS)
        val saved = findSavedFeedReturn()
        // The getter reads its flag before it returns, so its return is hooked first, while the
        // reads' indexes still hold.
        defaultSavedFeed(saved)
        answerFlagReads(reads, FEED_FLAG)
        enableStatus("followingFeed")
    }
}

/** Where the saved feed's getter hands back the name it read: the class, the method, and the return's index and register. */
internal class SavedFeedReturn(
    val type: String,
    val name: String,
    val returnAt: Int,
    val register: Int,
)

/**
 * Finds the one class whose constructor names [SAVED_FEED_KEY], its one getter (an instance method
 * taking nothing and answering a String) that loads [REMEMBERED_FEED_FLAG], and in it the
 * return of the name it read, cast to a String. Fails when any of them isn't exactly one, since
 * that's an update the patch hasn't seen.
 */
internal fun BytecodePatchContext.findSavedFeedReturn(): SavedFeedReturn {
    val holders = mutableListOf<Pair<String, List<Method>>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        if (classDef.methods.any { it.name == "<init>" && SAVED_FEED_KEY in it.strings() }) {
            holders += classDef.type to classDef.methods.toList()
        }
    }
    val (type, methods) = holders.singleOrNull()
        ?: refuse("expected one class keeping $SAVED_FEED_KEY, found ${holders.size}")
    val getters = methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "<init>" &&
            it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    }
    val getter = getters.singleOrNull() ?: refuse("$type has ${getters.size} getters of the saved feed, expected one")
    val code = getter.implementation!!.instructions.toList()
    val where = "$type->${getter.name}"
    if (code.none { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == REMEMBERED_FEED_FLAG }) {
        refuse("$where doesn't ask the remembered feed flag")
    }
    val returns = code.indices.filter { at ->
        val cast = code.getOrNull(at - 1)
        code[at].opcode == Opcode.RETURN_OBJECT && cast?.opcode == Opcode.CHECK_CAST &&
            (cast as ReferenceInstruction).reference.toString() == "Ljava/lang/String;" &&
            (cast as OneRegisterInstruction).registerA == (code[at] as OneRegisterInstruction).registerA
    }
    val returnAt = returns.singleOrNull() ?: refuse("$where returns a saved name ${returns.size} times, expected once")
    return SavedFeedReturn(type, getter.name, returnAt, (code[returnAt] as OneRegisterInstruction).registerA)
}

/** Passes the saved feed's name through [SAVED_FEED] on its way out of the getter. */
internal fun BytecodePatchContext.defaultSavedFeed(saved: SavedFeedReturn) {
    val method = mutableClassDefBy(saved.type).methods.single {
        it.name == saved.name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    }
    val name = saved.register
    method.addInstructionsAtControlFlowLabel(
        saved.returnAt,
        """
            invoke-static/range { v$name .. v$name }, $SAVED_FEED
            move-result-object v$name
        """,
    )
}

private fun Method.strings(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    ?.toSet() ?: emptySet()

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
