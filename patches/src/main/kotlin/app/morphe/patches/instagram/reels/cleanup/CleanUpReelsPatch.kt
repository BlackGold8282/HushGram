/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Clean up Reels"

/**
 * Takes three things off the Reels viewer, each behind its own switch, all on once the patch is in:
 * the Follow button beside a reel's author, the pills that prompt you to make something or promote
 * something, and friends' activity with the comment preview. See ReelParts.kt for each part.
 *
 * Every part is required: a build where one can't be found once, in the shape its kind has, stops
 * the patch with what's wrong, rather than shipping a switch that quietly does nothing.
 */
@Suppress("unused")
val cleanUpReelsPatch = bytecodePatch(
    name = "Clean up Reels",
    description = "Hides the Follow button on reels, the pills that push Edits, templates, Meta AI and " +
        "Ray-Ban Meta glasses, and friends' activity with the comment preview. Each part has its own switch.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        hideReelParts()
        enableStatus("reelDeclutter")
    }
}

/**
 * Finds the method behind each of [REEL_PARTS] by its marker and asks the part's hook first thing
 * in it. A render returns null when the hook says to hide, which Instagram's own renders answer
 * whenever they have nothing to draw. The check returns false.
 */
internal fun BytecodePatchContext.hideReelParts() {
    val wanted = REEL_PARTS.associateBy { it.marker }
    val found = mutableMapOf<String, MutableList<Method>>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            method.markers().filter { it in wanted }.distinct().forEach { found.getOrPut(it) { mutableListOf() } += method }
        }
    }

    val methods = REEL_PARTS.associateWith { part ->
        val holders = found[part.marker].orEmpty()
        holders.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one method holding the ${part.marker} marker, found " +
                if (holders.isEmpty()) "none" else holders.joinToString { "${it.definingClass}->${it.name}" },
        )
    }
    methods.forEach { (part, method) -> requireShape(part, method) }
    val renders = methods.filterKeys { !it.check }.values
    val shapes = renders.map { it.parameterTypes.single().toString() to it.returnType }.toSet()
    if (shapes.size != 1) throw PatchException("$PATCH: the renders don't share one shape: $shapes")

    methods.forEach { (part, found) ->
        val method = mutableClassDefBy(found.definingClass).methods.single {
            it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString) &&
                it.returnType == found.returnType
        }
        method.requireLocals(PATCH, 1)
        val answer = if (part.check) "return v0" else "return-object v0"
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, ${part.hook}
                move-result v0
                if-eqz v0, :draw
                const/4 v0, 0x0
                $answer
            """,
            ExternalLabel("draw", method.getInstruction(0)),
        )
    }
}

/** A render takes one component scope and answers an object; the check is static and answers a boolean. */
private fun requireShape(part: ReelPart, method: Method) {
    val static = AccessFlags.STATIC.isSet(method.accessFlags)
    val problem = when {
        method.implementation == null -> "has no body"
        part.check && (!static || method.returnType != "Z") -> "is not a static check answering a boolean"
        !part.check && (static || method.parameterTypes.size != 1 || !method.returnType.startsWith("L")) ->
            "is not a render taking one scope and answering a component"
        else -> null
    } ?: return
    throw PatchException("$PATCH: ${method.definingClass}->${method.name}, holding the ${part.marker} marker, $problem")
}

/** The part names of the markers [this] method loads. */
internal fun Method.markers(): List<String> =
    implementation?.instructions?.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) return@mapNotNull null
        val string = ((instruction as ReferenceInstruction).reference as StringReference).string
        if (!string.startsWith("android_purge_")) null else PURGE_MARKER.find(string)?.groupValues?.get(1)
    }.orEmpty()
