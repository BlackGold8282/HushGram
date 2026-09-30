/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.doubletap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Turn off double tap to like"
private const val DOUBLE_TAP_LIKE = "$EXTENSION_PACKAGE/reels/DoubleTapLike;"
internal const val HOLD_BACK_POST = "$DOUBLE_TAP_LIKE->holdBackPost()Z"
internal const val LIKE_ACTION = "$DOUBLE_TAP_LIKE->likeAction(Ljava/lang/Object;)Ljava/lang/Object;"

/** The report the feed's onDoubleTapMedia files when it has no activity: the one string it holds. */
internal const val FEED_DOUBLE_TAP = "DefaultMediaHolderGestureDetectorDelegateImpl#onDoubleTapMedia called with null activity"

/** The Reels gesture handler's double tap, and its setter for the action a double tap likes through. */
internal const val HANDLE_DOUBLE_TAP = "GestureActionHandler_handleDoubleTapMedia"
internal const val SET_LIKE_ACTION = "GestureActionHandler_setOnLikeMediaAction"

/**
 * Keeps a double tap on a post or a reel from liking it. See DoubleTapLike in the extension for
 * where each double tap likes and what the hooks ask.
 *
 * Off in the default selection, as Hushfacebook's is: a double tap to like is a gesture people use on
 * purpose, so taking it away is a choice to make. Picked, its switch starts on.
 */
@Suppress("unused")
val turnOffDoubleTapLikePatch = bytecodePatch(
    name = "Turn off double tap to like",
    description = "Stops a double tap on a post or a reel from liking it, and the heart doesn't show. A single tap " +
        "still does what it did, and the Like button still likes.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        // Both anchors are found and checked, and SettingsStatus is confirmed to carry the switch's
        // method, before either hook changes an instruction.
        requireStatusMethod("doubleTapLike")
        turnOffDoubleTapLikes()
        enableStatus("doubleTapLike")
    }
}

/** Finds and checks both double taps, then hooks them. A build that fails any check is left as it was. */
internal fun BytecodePatchContext.turnOffDoubleTapLikes() {
    val found = findDoubleTaps()
    holdBackPostDoubleTap(found)
    emptyReelLikeAction(found)
}

/** The two double taps: the feed's, and the Reels handler's read of its like action at [read]. */
internal class DoubleTaps(val post: Method, val reel: Method, val read: Int, val action: FieldReference)

internal fun BytecodePatchContext.findDoubleTaps(): DoubleTaps {
    val posts = mutableListOf<Method>()
    val reels = mutableListOf<Method>()
    val setters = mutableListOf<Method>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.code()
            if (code.any { it.stringLoaded() == FEED_DOUBLE_TAP }) posts += method
            val markers = method.markers()
            if (HANDLE_DOUBLE_TAP in markers) reels += method
            if (SET_LIKE_ACTION in markers) setters += method
        }
    }
    val post = posts.singleOrNull() ?: refuse("expected one feed double tap holding \"$FEED_DOUBLE_TAP\", found ${posts.size}")
    if (AccessFlags.STATIC.isSet(post.accessFlags) || post.returnType != "V") {
        refuse("the feed double tap ${post.definingClass}->${post.name} isn't an instance method returning nothing")
    }
    // The guard borrows v0 at index 0.
    if (post.localRegisterCount() < 1) refuse("the feed double tap ${post.definingClass}->${post.name} has no local register")

    val reel = reels.singleOrNull() ?: refuse("expected one method marked $HANDLE_DOUBLE_TAP, found ${reels.size}")
    val setter = setters.singleOrNull() ?: refuse("expected one method marked $SET_LIKE_ACTION, found ${setters.size}")
    if (setter.definingClass != reel.definingClass) refuse("$SET_LIKE_ACTION isn't in ${reel.definingClass}")
    val written = setter.code().filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull { it.fieldReference() }
        .filter { it.definingClass == reel.definingClass }.distinctBy { it.toString() }
    val action = written.singleOrNull() ?: refuse("$SET_LIKE_ACTION writes ${written.size} fields of ${reel.definingClass}, expected one")

    // The handler reads its like action once and skips the like when it's null.
    val code = reel.code()
    val reads = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && code[it].fieldReference()?.toString() == action.toString() }
    val read = reads.singleOrNull() ?: refuse("${reel.definingClass}->${reel.name} reads its like action ${reads.size} times, expected once")
    val register = (code[read] as TwoRegisterInstruction).registerA
    val check = code.getOrNull(read + 1)
    if (check?.opcode != Opcode.IF_EQZ || (check as OneRegisterInstruction).registerA != register) {
        refuse("${reel.definingClass}->${reel.name} doesn't check its like action for null straight after reading it")
    }
    return DoubleTaps(post, reel, read, action)
}

/** First thing in the feed's double tap: return while the switch holds it back. */
private fun BytecodePatchContext.holdBackPostDoubleTap(found: DoubleTaps) {
    mutable(found.post).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $HOLD_BACK_POST
                move-result v0
                if-eqz v0, :tap
                return-void
            """,
            ExternalLabel("tap", getInstruction(0)),
        )
    }
}

/**
 * After the Reels handler reads its like action, the extension's answer in its place: the action,
 * or null while the switch holds the double tap back. The range form passes the register whatever
 * its number.
 */
private fun BytecodePatchContext.emptyReelLikeAction(found: DoubleTaps) {
    val method = mutable(found.reel)
    val register = (method.getInstruction(found.read) as TwoRegisterInstruction).registerA
    method.addInstructions(
        found.read + 1,
        """
            invoke-static/range { v$register .. v$register }, $LIKE_ACTION
            move-result-object v$register
            check-cast v$register, ${found.action.type}
        """,
    )
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
