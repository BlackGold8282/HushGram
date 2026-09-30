/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

/** Instagram's post, reel or story, which keeps its name. */
internal const val MEDIA = "Lcom/instagram/feed/media/Media;"
internal const val USER = "Lcom/instagram/user/model/User;"
internal const val VIDEO_VERSION = "Lcom/instagram/api/schemas/VideoVersionIntf;"
internal const val PANDO_VIDEO_VERSION = "Lcom/instagram/api/schemas/ImmutablePandoVideoVersion;"

/** The extension's bridges to Instagram's media model, whose bodies [writeMediaBridges] writes. */
internal const val INSTAGRAM_MEDIA = "$EXTENSION_PACKAGE/download/InstagramMedia;"

/**
 * Instagram's getter on [type] for the model field [field]: the one method taking nothing and
 * answering [returns] that loads the field's key. Instagram's models read a field from the tree
 * the server sent by the Java hash of its name, so a getter holds that hash as a constant however
 * the release shortened the getter's own name. None, or two, stop the patch naming [patch].
 */
internal fun BytecodePatchContext.pandoGetter(patch: String, type: String, field: String, returns: String): Method {
    val key = field.hashCode()
    val getters = classDefBy(type).methods.filter { method ->
        method.parameterTypes.isEmpty() && method.returnType == returns && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any { it.loadsLiteral(key) } == true
    }
    return getters.singleOrNull() ?: throw PatchException(
        "$patch: expected one getter on $type answering $returns for $field, found " +
            if (getters.isEmpty()) "none" else getters.joinToString { it.name },
    )
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.loadsLiteral(value: Int): Boolean =
    (opcode == Opcode.CONST || opcode == Opcode.CONST_16 || opcode == Opcode.CONST_HIGH16 || opcode == Opcode.CONST_4) &&
        (this as NarrowLiteralInstruction).narrowLiteral == value

/** One bridge: the extension method [name], and the Instagram call [call] its body makes on the argument cast to [receiver]. */
private class Bridge(val name: String, val receiver: String, val call: String)

/**
 * Finds what the body of each of the extension's InstagramMedia bridges calls, and answers the
 * step that writes them: the argument cast to Instagram's type and handed to the getter that reads
 * the field, found by [pandoGetter] or, for the media's id, by its kept name. Everything is found
 * here, so a build where one is missing stops the patch naming [patch] before anything changes.
 *
 * Each body uses its parameter register alone, so the stub's own register count doesn't matter,
 * and goes in first, ahead of the stub's own `return null`, which is then never reached.
 */
internal fun BytecodePatchContext.mediaBridges(patch: String): () -> Unit {
    val videoVersions = pandoGetter(patch, MEDIA, "video_versions", "Ljava/util/List;")
    val dashManifest = pandoGetter(patch, MEDIA, "video_dash_manifest", "Ljava/lang/String;")
    val owner = pandoGetter(patch, MEDIA, "user", USER)
    val takenAt = pandoGetter(patch, MEDIA, "taken_at", "Ljava/lang/Long;")
    val username = pandoGetter(patch, USER, "username", "Ljava/lang/String;")
    val mediaId = classDefBy(MEDIA).methods.singleOrNull {
        it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("$patch: $MEDIA has no getId()")
    // A version comes as either of Instagram's two classes, so it's read through their interface,
    // by the name the tree-backed class gives each getter.
    val versionInterface = classDefBy(VIDEO_VERSION).methods
    fun version(field: String, returns: String): String {
        val getter = pandoGetter(patch, PANDO_VIDEO_VERSION, field, returns)
        if (versionInterface.none { it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == returns }) {
            throw PatchException("$patch: $VIDEO_VERSION doesn't declare ${getter.name}, the getter for $field")
        }
        return "invoke-interface {p0}, $VIDEO_VERSION->${getter.name}()$returns"
    }
    fun virtual(getter: Method) = "invoke-virtual {p0}, ${getter.definingClass}->${getter.name}()${getter.returnType}"

    val bridges = listOf(
        Bridge("videoVersions", MEDIA, virtual(videoVersions)),
        Bridge("dashManifest", MEDIA, virtual(dashManifest)),
        Bridge("mediaId", MEDIA, virtual(mediaId)),
        Bridge("owner", MEDIA, virtual(owner)),
        Bridge("takenAt", MEDIA, virtual(takenAt)),
        Bridge("username", USER, virtual(username)),
        Bridge("versionUrl", VIDEO_VERSION, version("url", "Ljava/lang/String;")),
        Bridge("versionWidth", VIDEO_VERSION, version("width", "Ljava/lang/Integer;")),
        Bridge("versionHeight", VIDEO_VERSION, version("height", "Ljava/lang/Integer;")),
    )
    val stubs = mutableClassDefBy(INSTAGRAM_MEDIA).methods
    val found = bridges.associateWith { bridge ->
        stubs.singleOrNull {
            it.name == bridge.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
        } ?: throw PatchException("$patch: $INSTAGRAM_MEDIA has no static ${bridge.name}(Object)")
    }
    return {
        found.forEach { (bridge, stub) ->
            stub.addInstructions(
                0,
                """
                    check-cast p0, ${bridge.receiver}
                    ${bridge.call}
                    move-result-object p0
                    return-object p0
                """,
            )
        }
    }
}
