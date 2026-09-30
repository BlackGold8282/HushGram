/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.mediaBridges
import app.morphe.patches.instagram.download.reel.DOWNLOAD
import app.morphe.patches.instagram.download.reel.ELIGIBLE_MARKER
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.download.reel.code
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Download any video"

private const val VIDEO_DOWNLOAD = "$EXTENSION_PACKAGE/download/VideoDownload;"
internal const val OFFER_VIDEO = "$VIDEO_DOWNLOAD->offer(ZLjava/lang/Object;)Z"
internal const val WITHHOLD_VIDEO = "$VIDEO_DOWNLOAD->withhold(Z)Z"
internal const val SAVE_VIDEO = "$VIDEO_DOWNLOAD->save(Ljava/lang/Object;Landroid/app/Activity;)Z"

/** The name Instagram's build keeps, in a static field, for the class that runs a feed post's menu. */
internal const val FEED_HELPER_NAME = "MediaOptionsOverflowHelper"
private const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"

/**
 * Download in the menu of a feed post with a video, saving through HushGram's own pipeline.
 *
 * Instagram 449's feed menu has a Download row of its own. Its builder shows the row only when the
 * post's owner lets other people download it, then holds it back again while a server flag says
 * so, and a tap saves a copy with a watermark. This patch shows the row on every post with a video
 * and has a tap save the video from the addresses its Media already holds, the way Hushfacebook's
 * Download any video does.
 *
 * Everything is found before anything changes, so a build that differs stops the patch naming
 * what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val downloadVideoPatch = bytecodePatch(
    name = "Download any video",
    description = "Adds Download to the menu of a post in your feed with a video. Videos save at the Download quality you set, " +
        "without Instagram's watermark.",
    default = false,
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("videoDownload")
        offerDownloadOnEveryVideo()
        enableStatus("videoDownload")
    }
}

/** Where the feed menu's builder decides on the Download row: after the move-result at [at] - 1, in [register]. */
internal class VideoGate(val at: Int, val register: Int, val media: Int?, val hook: String)

internal fun BytecodePatchContext.offerDownloadOnEveryVideo() {
    val helpers = mutableListOf<ClassDef>()
    val eligibles = mutableListOf<Method>()
    val loaders = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.originalName() == FEED_HELPER_NAME) helpers += classDef
        classDef.methods.forEach { method ->
            if (ELIGIBLE_MARKER in method.markers()) eligibles += method
            if (method.code().any { it.opcode == Opcode.SGET_OBJECT && it.referenceText() == DOWNLOAD }) loaders += method
        }
    }
    val helper = helpers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one class named $FEED_HELPER_NAME, found " + if (helpers.isEmpty()) "none" else helpers.joinToString { it.type },
    )
    val eligible = eligibles.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method holding the $ELIGIBLE_MARKER marker, found ${eligibles.size}",
    )
    if (eligible.returnType != "Z" || MEDIA !in eligible.parameterTypes.map(Any::toString)) {
        throw PatchException("$PATCH: ${eligible.definingClass}->${eligible.name}, the download check, doesn't answer a boolean for a Media")
    }
    val type = helper.type
    val builders = loaders.filter { it.calls(eligible) && it.uses(type) }
    val builder = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one builder of the feed menu adding Download after the download check, found " +
            if (builders.isEmpty()) "none" else builders.joinToString { "${it.definingClass}->${it.name}" },
    )
    val gates = builder.videoGates(eligible)
    val handlers = helper.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(OPTION)
    }
    val handler = handlers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one handler of a tapped option in $type, found ${handlers.size}")
    // Two static getters answer the post: one reads it, the other calls that one and throws on null.
    val getters = helper.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == MEDIA &&
            method.parameterTypes.map(Any::toString) == listOf(type) &&
            method.code().any { (it as? ReferenceInstruction)?.reference.let { field -> field is FieldReference && field.definingClass == type } }
    }
    val media = getters.singleOrNull()
        ?: throw PatchException("$PATCH: expected one getter reading the post in $type, found ${getters.size}")
    val activities = helper.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == FRAGMENT_ACTIVITY }
    val activity = activities.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $FRAGMENT_ACTIVITY field in $type, found ${activities.size}")
    val menu = mutable(handler)
    menu.requireLocals(PATCH, 3)
    val writeBridges = mediaBridges(PATCH)

    val method = mutable(builder)
    gates.sortedByDescending { it.at }.forEach { gate ->
        val call = if (gate.media != null) "invoke-static { v${gate.register}, v${gate.media} }" else "invoke-static { v${gate.register} }"
        method.addInstructions(
            gate.at,
            """
                $call, ${gate.hook}
                move-result v${gate.register}
            """,
        )
    }

    menu.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            sget-object v1, $DOWNLOAD
            if-ne v0, v1, :handle
            move-object/from16 v0, p0
            invoke-static { v0 }, $type->${media.name}($type)$MEDIA
            move-result-object v1
            iget-object v2, v0, $type->${activity.name}:$FRAGMENT_ACTIVITY
            invoke-static { v1, v2 }, $SAVE_VIDEO
            move-result v0
            if-eqz v0, :handle
            return-void
        """,
        ExternalLabel("handle", menu.getInstruction(0)),
    )
    writeBridges()
}

/**
 * The filters of [this], the feed menu's builder, that let the Download row in. It calls the
 * download check once, and the answer, with the Media the check was asked about, goes through
 * offer(). The row itself is loaded once, out of line, and reached only by branches that jump to it
 * when a test is false: a flag read from a call goes through withhold(), and a field's flag, which
 * only picks whether the server flag is read, is left alone. Anything else stops the patch.
 */
internal fun Method.videoGates(eligible: Method): List<VideoGate> {
    val code = code()
    val where = "$definingClass->$name"
    val row = code.indices.singleOrNull { code[it].opcode == Opcode.SGET_OBJECT && code[it].referenceText() == DOWNLOAD }
        ?: throw PatchException("$PATCH: $where doesn't load Download once")
    val check = code.indices.singleOrNull { code[it].calls(eligible) }
        ?: throw PatchException("$PATCH: $where doesn't call the download check once")
    val answer = code.getOrNull(check + 1)
    if (answer?.opcode != Opcode.MOVE_RESULT) throw PatchException("$PATCH: $where doesn't keep the download check's answer")
    val register = (answer as OneRegisterInstruction).registerA
    val static = AccessFlags.STATIC.isSet(eligible.accessFlags)
    val argument = (if (static) 0 else 1) + eligible.parameterTypes.map(Any::toString).indexOf(MEDIA)
    val media = code[check].argumentRegisters()[argument]
    if (media == register || media > 15 || register > 15) {
        throw PatchException("$PATCH: in $where the download check's answer v$register and its Media v$media don't fit the filter")
    }
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    fun target(index: Int) = address.indexOf(address[index] + (code[index] as OffsetInstruction).codeOffset)
    val jumps = code.indices.filter { code[it] is OffsetInstruction && code[it].opcode.name.let { name -> name.startsWith("if-") || name.startsWith("goto") } }
    if (jumps.any { target(it) == check + 2 }) throw PatchException("$PATCH: in $where a jump lands after the download check")
    val skip = code.getOrNull(check + 2)
    if (skip?.opcode != Opcode.IF_EQZ || (skip as OneRegisterInstruction).registerA != register) {
        throw PatchException("$PATCH: in $where the download check's answer isn't tested right after it")
    }
    val gates = mutableListOf(VideoGate(check + 2, register, media, OFFER_VIDEO))
    val intoRow = jumps.filter { target(it) == row }
    if (intoRow.isEmpty() || code[row - 1].opcode.let { it != Opcode.GOTO && it != Opcode.GOTO_16 && it != Opcode.GOTO_32 && it != Opcode.RETURN_OBJECT }) {
        throw PatchException("$PATCH: in $where Download isn't a row reached only by jumps")
    }
    intoRow.forEach { index ->
        val branch = code[index]
        if (branch.opcode != Opcode.IF_EQZ || index < check) {
            throw PatchException("$PATCH: in $where the jump at $index reaches Download in a way this patch doesn't know")
        }
        val tested = (branch as OneRegisterInstruction).registerA
        val set = (index - 1 downTo check + 1).firstOrNull { code[it].writes(tested) }
            ?: throw PatchException("$PATCH: in $where the jump at $index tests a register set before the download check")
        when (code[set].opcode) {
            Opcode.IGET_BOOLEAN -> Unit
            Opcode.MOVE_RESULT -> {
                val call = (code[set - 1] as? ReferenceInstruction)?.reference as? MethodReference
                if (call?.returnType != "Z") throw PatchException("$PATCH: in $where the jump at $index doesn't test a call's answer")
                if (jumps.any { target(it) == set + 1 }) throw PatchException("$PATCH: in $where a jump lands after the flag at $set")
                gates += VideoGate(set + 1, tested, null, WITHHOLD_VIDEO)
            }
            else -> throw PatchException("$PATCH: in $where the jump at $index tests something this patch doesn't know")
        }
    }
    if (gates.none { it.hook == WITHHOLD_VIDEO }) throw PatchException("$PATCH: in $where no flag holds Download back")
    return gates
}

private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is Instruction35c -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

private fun Instruction.calls(method: Method): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.definingClass == method.definingClass && reference.name == method.name &&
        reference.returnType == method.returnType &&
        reference.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
}

private fun Method.calls(method: Method): Boolean = code().any { it.calls(method) }

/** Whether [this] reads or calls anything of [type]. */
private fun Method.uses(type: String): Boolean = code().any { instruction ->
    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        is FieldReference -> reference.definingClass == type
        is MethodReference -> reference.definingClass == type
        else -> false
    }
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }
