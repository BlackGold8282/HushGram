/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.taptoplay

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
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Tap to play"

private const val TAP_TO_PLAY = "$EXTENSION_PACKAGE/media/TapToPlay;"
internal const val ALLOW_START = "$TAP_TO_PLAY->allowStart(Ljava/lang/Object;Ljava/lang/String;)Z"
internal const val ALLOW_DIRECT_START = "$TAP_TO_PLAY->allowDirectStart(Ljava/lang/Object;Ljava/lang/String;)Z"
internal const val PAUSED = "$TAP_TO_PLAY->paused(Ljava/lang/Object;Ljava/lang/String;)V"
internal const val REBOUND = "$TAP_TO_PLAY->rebound(Ljava/lang/Object;)V"
internal const val AUTOPLAY_ALLOWED = "$TAP_TO_PLAY->autoplayAllowed(Z)Z"
internal const val TOUCH = "$EXTENSION_PACKAGE/media/TapClock;->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)V"

/** The strings Instagram's build keeps in its players' own log lines, which pick each method out. */
internal const val PLAY_INTERNAL = "IgVideoPlayerImpl.playInternal playAfterSeek: "
internal const val GROOT_PREPARE = "IgGrootPlayer.prepare"
internal val GROOT_PLAY = listOf("retry", "play_after_recovery")
internal val AUTOPLAY_CHECKER = listOf("VideoAutoplayChecker", "zero_rating_or_data_saver")

/** The activity every Instagram screen extends, which Redex leaves under its own name. */
internal const val FRAGMENT_ACTIVITY = "Lcom/instagram/base/activity/IgFragmentActivity;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val STRING = "Ljava/lang/String;"

/**
 * Videos, reels and stories wait for a tap.
 *
 * Instagram 449 plays through IgGrootPlayer. A feed video or a reel starts through
 * IgVideoPlayerImpl's playInternal, which plays the IgGrootPlayer it holds; the story viewer and a
 * few other screens play an IgGrootPlayer themselves. Both starts ask the extension first, handing
 * it that IgGrootPlayer, and a start no tap asked for returns before it does anything. playInternal
 * is gated before it marks the video as playing, so a later tap finds it stopped and starts it.
 * IgGrootPlayer's pause and prepare tell the extension when what a tap started has ended, every
 * touch on an Instagram screen goes past the tap clock, and Instagram's own autoplay check answers
 * no, so the feed draws its play button.
 *
 * Everything is found before anything changes, so a build that differs stops the patch naming
 * what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val tapToPlayPatch = bytecodePatch(
    name = "Tap to play",
    description = "Videos, reels and stories wait for your tap instead of starting by themselves. Feed videos show a play " +
        "button, the way they do when Instagram saves mobile data.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("tapToPlay")
        holdStartsWithoutATap()
        enableStatus("tapToPlay")
    }
}

/** Every method this patch changes, found and checked before any of them is. */
internal class PlayerHooks(
    val playInternal: Method,
    val grootField: FieldReference,
    val play: Method,
    val pause: Method,
    val prepare: Method,
    val checker: Method,
    val touch: Method,
)

internal fun BytecodePatchContext.holdStartsWithoutATap() {
    val hooks = findPlayerHooks()

    mutable(hooks.playInternal).apply {
        requireLocals(PATCH, 1)
        addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, ${hooks.grootField}
                invoke-static { v0, p1 }, $ALLOW_START
                move-result v0
                if-nez v0, :start
                return-void
            """,
            ExternalLabel("start", getInstruction(0)),
        )
    }
    mutable(hooks.play).apply {
        requireLocals(PATCH, 1)
        addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p1 }, $ALLOW_DIRECT_START
                move-result v0
                if-nez v0, :start
                return-void
            """,
            ExternalLabel("start", getInstruction(0)),
        )
    }
    mutable(hooks.pause).addInstructions(0, "invoke-static/range { p0 .. p1 }, $PAUSED")
    mutable(hooks.prepare).addInstructions(0, "invoke-static/range { p0 .. p0 }, $REBOUND")
    mutable(hooks.checker).apply {
        // The call goes in at each return's own label, so a branch straight to a return passes
        // through it too.
        implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN }
            .map { it.index to (it.value as OneRegisterInstruction).registerA }
            .asReversed()
            .forEach { (index, register) ->
                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static/range { v$register .. v$register }, $AUTOPLAY_ALLOWED
                        move-result v$register
                    """,
                )
            }
    }
    mutable(hooks.touch).addInstructions(0, "invoke-static/range { p0 .. p1 }, $TOUCH")
}

/**
 * The players' play, pause and prepare, the autoplay check and the touch dispatch, each the one
 * method of its shape holding its strings. playInternal must read the IgGrootPlayer it plays from
 * a field of its own player, so the hook can hand that player over first thing; the play and
 * prepare must be IgGrootPlayer's, the class holding [GROOT_PREPARE].
 */
internal fun BytecodePatchContext.findPlayerHooks(): PlayerHooks {
    val internals = mutableListOf<Method>()
    val preparers = mutableListOf<Method>()
    val checkers = mutableListOf<Method>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            val strings = method.strings()
            if (PLAY_INTERNAL in strings) internals += method
            if (GROOT_PREPARE in strings) preparers += method
            if (strings.containsAll(AUTOPLAY_CHECKER)) checkers += method
        }
    }
    val playInternal = internals.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding \"$PLAY_INTERNAL\", found ${internals.size}")
    val owner = playInternal.definingClass
    if (!AccessFlags.STATIC.isSet(playInternal.accessFlags) || playInternal.returnType != "V" ||
        playInternal.parameterTypes.map(Any::toString) != listOf(owner, STRING, "Z", "Z")
    ) {
        throw PatchException("$PATCH: $owner->${playInternal.name}, playInternal, isn't static void ($owner, String, boolean, boolean)")
    }
    if (playInternal.parameterRegisterNumber(1) > 15) {
        throw PatchException("$PATCH: $owner->${playInternal.name} keeps its start reason past v15")
    }

    val prepare = preparers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding \"$GROOT_PREPARE\", found ${preparers.size}")
    val groot = classDefBy(prepare.definingClass)
    if (AccessFlags.STATIC.isSet(prepare.accessFlags) || prepare.returnType != "V") {
        throw PatchException("$PATCH: ${groot.type}->${prepare.name}, IgGrootPlayer's prepare, isn't an instance method returning nothing")
    }

    val plays = groot.instanceMethods { it.parameterTypes.map(Any::toString) == listOf(STRING, "Z") && it.strings().containsAll(GROOT_PLAY) }
    val play = plays.singleOrNull()
        ?: throw PatchException("$PATCH: expected one play (String, boolean) in ${groot.type} holding $GROOT_PLAY, found ${plays.size}")

    // IgGrootPlayer's pause takes the reason and hands it on to the player underneath, and holds
    // no string of its own. Its other (String) method holds log lines.
    val pauses = groot.instanceMethods { method ->
        method.parameterTypes.map(Any::toString) == listOf(STRING) && method.strings().isEmpty() &&
            method.code().any { instruction ->
                val call = instruction.methodReference()
                instruction.opcode == Opcode.INVOKE_VIRTUAL && call != null && call.definingClass != groot.type &&
                    call.returnType == "V" && call.parameterTypes.map(Any::toString) == listOf(STRING)
            }
    }
    val pause = pauses.singleOrNull()
        ?: throw PatchException("$PATCH: expected one pause (String) in ${groot.type} handing its reason on, found ${pauses.size}")

    val grootField = playInternal.grootField(groot.type, play)

    val checker = checkers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding $AUTOPLAY_CHECKER, found ${checkers.size}")
    if (AccessFlags.STATIC.isSet(checker.accessFlags) || checker.returnType != "Z" || checker.parameterTypes.isNotEmpty()) {
        throw PatchException("$PATCH: ${checker.definingClass}->${checker.name}, the autoplay check, isn't an instance method answering a boolean")
    }
    if (checker.code().none { it.opcode == Opcode.RETURN }) {
        throw PatchException("$PATCH: ${checker.definingClass}->${checker.name}, the autoplay check, never returns")
    }

    val activity = classDefByOrNull(FRAGMENT_ACTIVITY)
        ?: throw PatchException("$PATCH: this build has no $FRAGMENT_ACTIVITY")
    val touch = activity.methods.singleOrNull {
        it.name == "dispatchTouchEvent" && it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf(MOTION_EVENT) &&
            it.implementation != null
    } ?: throw PatchException("$PATCH: $FRAGMENT_ACTIVITY has no dispatchTouchEvent of its own")

    return PlayerHooks(playInternal, grootField, play, pause, prepare, checker, touch)
}

/**
 * The field of its own player that [this], playInternal, reads the IgGrootPlayer of [grootType]
 * from before it calls [play] on it. Every such read must be of the same field, from the player
 * playInternal was handed, since the hook reads it first thing.
 */
private fun Method.grootField(grootType: String, play: Method): FieldReference {
    val code = code()
    val reads = code.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IGET_OBJECT && (instruction.fieldReference())?.let {
            it.definingClass == definingClass && it.type == grootType
        } == true
    }
    val player = parameterRegisterNumber(0)
    val fields = reads.map { it.value.fieldReference()!! }.distinctBy { "${it.definingClass}->${it.name}" }
    val field = fields.singleOrNull()
        ?: throw PatchException("$PATCH: $definingClass->$name reads ${fields.size} IgGrootPlayer fields of its player, expected one")
    if (reads.any { (it.value as TwoRegisterInstruction).registerB != player }) {
        throw PatchException("$PATCH: $definingClass->$name reads an IgGrootPlayer from something other than its player")
    }
    val plays = code.withIndex().filter { (_, instruction) ->
        instruction.methodReference()?.let {
            it.definingClass == grootType && it.name == play.name &&
                it.parameterTypes.map(Any::toString) == play.parameterTypes.map(Any::toString)
        } == true
    }
    if (plays.isEmpty()) throw PatchException("$PATCH: $definingClass->$name never plays its IgGrootPlayer")
    return field
}

private fun ClassDef.instanceMethods(filter: (Method) -> Boolean): List<Method> =
    methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.implementation != null && filter(it) }

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.strings(): Set<String> = code().mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}.toSet()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
