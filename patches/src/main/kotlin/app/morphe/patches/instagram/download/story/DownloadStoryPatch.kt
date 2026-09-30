/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.story

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.imageBridges
import app.morphe.patches.instagram.download.mediaBridges
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

private const val PATCH = "Download any story"

private const val STORY_DOWNLOAD = "$EXTENSION_PACKAGE/download/StoryDownload;"
internal const val LABELS = "$STORY_DOWNLOAD->labels([Ljava/lang/CharSequence;)[Ljava/lang/CharSequence;"
internal const val SAVE_STORY = "$STORY_DOWNLOAD->save(Ljava/lang/CharSequence;Ljava/lang/Object;)Z"

/** The name Instagram's build keeps, in a static field, for the class that runs a story's menu. */
internal const val HELPER_NAME = "ReelOptionsOverflowHelper"
private const val ORIGINAL_NAME_FIELD = "__redex_internal_original_name"

/** A story, which keeps its name. Instagram calls stories reels, and Reels clips. */
internal const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"
private const val LABEL_ARRAY = "[Ljava/lang/CharSequence;"
private const val LABEL = "Ljava/lang/CharSequence;"

/**
 * Download in the menu of anyone's story, saving through HushGram's own pipeline.
 *
 * A story's menu is built as a list of labels by one of a few static builders of the class that
 * runs it, and a tap hands the tapped label, with that class, to one of a few static handlers that
 * compare it with Instagram's own. Instagram offers a save only on your own stories. Each builder's
 * labels get Download added where it returns them, and each handler asks the extension first, which
 * saves the story when the label is Download.
 *
 * Everything is found before anything changes, so a build that differs stops the patch naming
 * what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val downloadStoryPatch = bytecodePatch(
    name = "Download any story",
    description = "Adds Download to the menu of anyone's story. A video saves at the Download quality you set, " +
        "a photo at its largest size.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("storyDownload")
        offerDownloadOnEveryStory()
        enableStatus("storyDownload")
    }
}

internal fun BytecodePatchContext.offerDownloadOnEveryStory() {
    val helpers = mutableListOf<ClassDef>()
    classDefForEach { classDef -> if (classDef.originalName() == HELPER_NAME) helpers += classDef }
    val helper = helpers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one class named $HELPER_NAME, found " + if (helpers.isEmpty()) "none" else helpers.joinToString { it.type },
    )
    val type = helper.type
    val statics = helper.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
    val builders = statics.filter { it.returnType == LABEL_ARRAY && type in it.parameterTypes.map(Any::toString) }
    if (builders.isEmpty()) throw PatchException("$PATCH: $type builds no story menu from its labels")
    val handlers = statics.filter { method ->
        val parameters = method.parameterTypes.map(Any::toString)
        method.returnType == "V" && parameters.lastOrNull() == LABEL && type in parameters
    }
    if (handlers.isEmpty()) throw PatchException("$PATCH: $type handles no tapped label")
    val returns = builders.associateWith { builder ->
        builder.labelReturns().ifEmpty { throw PatchException("$PATCH: $type->${builder.name} never returns its labels") }
    }

    val story = instanceField(type, REEL_ITEM)
    val media = builders.flatMap { builder ->
        builder.implementation!!.instructions.mapNotNull { instruction ->
            if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            if (field.definingClass == REEL_ITEM && field.type == MEDIA) "$REEL_ITEM->${field.name}:$MEDIA" else null
        }
    }.distinct().singleOrNull() ?: throw PatchException("$PATCH: the story menu's builders don't read one Media of a story")
    val storyMedia = mutableClassDefBy(INSTAGRAM_MEDIA).methods.singleOrNull {
        it.name == "storyMedia" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$PATCH: $INSTAGRAM_MEDIA has no static storyMedia(Object)")
    val handled = handlers.map { mutable(it) }
    handled.forEach { it.requireLocals(PATCH, 2) }
    val writeVideoBridges = mediaBridges(PATCH)
    val writeImageBridges = imageBridges(PATCH)

    // Each return of a builder hands its labels through the extension first. The return is
    // replaced rather than preceded, because a jump to the return lands on what replaces it and
    // would skip anything put in front of it.
    returns.forEach { (builder, found) ->
        val method = mutable(builder)
        found.sortedDescending().forEach { index ->
            val register = (method.getInstruction(index) as OneRegisterInstruction).registerA
            method.replaceInstruction(
                index,
                if (register > 15) "invoke-static/range { v$register .. v$register }, $LABELS" else "invoke-static { v$register }, $LABELS",
            )
            method.addInstructions(
                index + 1,
                """
                    move-result-object v$register
                    return-object v$register
                """,
            )
        }
    }

    handled.forEach { method ->
        val parameters = method.parameterTypes.map(Any::toString)
        val first = method.implementation!!.registerCount - parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        fun register(index: Int) = first + parameters.take(index).sumOf { if (it == "J" || it == "D") 2 else 1 }
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, v${register(parameters.lastIndex)}
                move-object/from16 v1, v${register(parameters.indexOf(type))}
                invoke-static { v0, v1 }, $SAVE_STORY
                move-result v0
                if-eqz v0, :handle
                return-void
            """,
            ExternalLabel("handle", method.getInstruction(0)),
        )
    }

    storyMedia.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $type
            iget-object p0, p0, $story
            if-eqz p0, :none
            iget-object p0, p0, $media
            :none
            return-object p0
        """,
    )
    writeVideoBridges()
    writeImageBridges()
}

/** The name Instagram's build kept for [this] class, or null. */
private fun ClassDef.originalName(): String? =
    fields.firstOrNull { it.name == ORIGINAL_NAME_FIELD && AccessFlags.STATIC.isSet(it.accessFlags) }
        ?.let { (it.initialValue as? StringEncodedValue)?.value }

/** The returns of [this] builder, each answering its labels. */
internal fun Method.labelReturns(): List<Int> =
    implementation?.instructions?.withIndex()?.filter { it.value.opcode == Opcode.RETURN_OBJECT }?.map { it.index }.orEmpty()

/** The one instance field of [type] in [owner], the story menu's class, as a field reference. */
private fun BytecodePatchContext.instanceField(owner: String, type: String): String {
    val fields = classDefBy(owner).fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }
    val field = fields.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $type field in $owner, the story menu's class, found ${fields.size}")
    return "$owner->${field.name}:$type"
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }
