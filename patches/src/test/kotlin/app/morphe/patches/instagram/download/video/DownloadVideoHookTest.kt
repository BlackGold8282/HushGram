/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.PANDO_VIDEO_VERSION
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.VIDEO_VERSION
import app.morphe.patches.instagram.download.reel.DOWNLOAD
import app.morphe.patches.instagram.download.reel.ELIGIBLE_MARKER
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadVideoHookTest {
    private val helper = "Lfixture/FeedMenu;"
    private val lambda = "Lfixture/MergedLambda;"
    private val util = "Lfixture/DownloadUtil;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val activity = "Landroidx/fragment/app/FragmentActivity;"
    private val check = "$util->A08($session$MEDIA)Z"
    private val flag = "Lfixture/MobileConfig;->A1A(Ljava/lang/Object;J)Z"

    /** The hooks the patch writes are in the extension the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(OFFER_VIDEO, WITHHOLD_VIDEO, SAVE_VIDEO)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The download check's answer goes through offer() with the Media it was asked about, and the
     * server flag's through withhold(). The field's flag, which only picks whether the server flag
     * is read, stays as it is.
     */
    @Test
    fun theFeedMenuOffersDownloadOnVideos() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        val code = context.method(lambda, "invoke").code()
        val at = code.indexOfFirst { it.referenceText() == check }
        assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals(OFFER_VIDEO, code[at + 2].referenceText())
        val offer = code[at + 2] as Instruction35c
        assertEquals("offer()'s arguments", listOf(1, 3), listOf(offer.registerC, offer.registerD))
        assertEquals("offer()'s answer", 1, (code[at + 3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[at + 4].opcode)

        val read = code.indexOfFirst { it.referenceText() == flag }
        assertEquals(WITHHOLD_VIDEO, code[read + 2].referenceText())
        assertEquals(Opcode.MOVE_RESULT, code[read + 3].opcode)
        assertEquals("one filter per gate", 2, code.count { it.referenceText()?.startsWith("Lapp/hushgram/") == true })
        val field = code.indexOfFirst { it.opcode == Opcode.IGET_BOOLEAN }
        assertEquals("the field's flag is filtered", Opcode.IF_EQZ, code[field + 1].opcode)
    }

    /** A tap on Download asks save() first, with the post and the menu's activity; any other option goes on. */
    @Test
    fun theHandlerAsksSaveFirst() {
        val context = PatchContexts.of(classes())

        context.offerDownloadOnEveryVideo()

        val code = context.method(helper, "A09").code()
        assertEquals(
            listOf(
                Opcode.MOVE_OBJECT_FROM16, Opcode.SGET_OBJECT, Opcode.IF_NE, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
            ),
            code.take(11).map { it.opcode },
        )
        assertEquals(DOWNLOAD, code[1].referenceText())
        assertEquals("the getter that reads the post", "$helper->A01($helper)$MEDIA", code[4].referenceText())
        assertEquals("$helper->activity:$activity", code[6].referenceText())
        assertEquals(SAVE_VIDEO, code[7].referenceText())
        assertEquals("the original code moved", Opcode.CONST_STRING, code[11].opcode)
        for (branch in listOf(2, 9)) assertEquals("the branch at $branch", 11, code.target(branch))
    }

    /** A flag that lets Download in only when it's on is a gate this patch doesn't know, and nothing changes. */
    @Test
    fun anUnknownJumpToDownloadFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(flagJumps = "if-nez"))
        assertThrows(PatchException::class.java) { context.offerDownloadOnEveryVideo() }
        assertUntouched(context)
    }

    @Test
    fun aMissingMenuClassFailsThePatch() {
        val context = PatchContexts.of(classes(name = "SomethingElse"))
        val failure = assertThrows(PatchException::class.java) { context.offerDownloadOnEveryVideo() }
        assertTrue(failure.message!!, failure.message!!.contains(FEED_HELPER_NAME))
    }

    @Test
    fun aHandlerWithoutLocalsFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(handlerRegisters = 3))
        assertThrows(PatchException::class.java) { context.offerDownloadOnEveryVideo() }
        assertUntouched(context)
    }

    /**
     * In each declared build, the feed menu's class is found by its kept name, its builder filters
     * the download check and at least one flag, the handler asks save() first, and every video
     * bridge is written.
     */
    @Test
    fun eachDeclaredBuildOffersDownloadOnEveryVideo() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val types = setOf(MEDIA, USER, VIDEO_VERSION, PANDO_VIDEO_VERSION)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>(ExtensionDex.classDef(INSTAGRAM_MEDIA))
                FixtureDex.forEach(bundle) { dex ->
                    val marked = dex.stringSection.any { it.startsWith("android_purge_") && PURGE_MARKER.find(it)?.groupValues?.get(1) == ELIGIBLE_MARKER }
                    val loads = dex.fieldSection.any { it.toString() == DOWNLOAD }
                    val named = dex.stringSection.any { it == FEED_HELPER_NAME }
                    if (!marked && !loads && !named && dex.classes.none { it.type in types }) return@forEach
                    for (classDef in dex.classes) {
                        val wanted = classDef.type in types || classDef.originalName() == FEED_HELPER_NAME || classDef.methods.any { method ->
                            ELIGIBLE_MARKER in method.markers() || method.code().any { it.referenceText() == DOWNLOAD }
                        }
                        if (wanted) classes += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(classes)

                context.offerDownloadOnEveryVideo()

                val menu = classes.single { it.originalName() == FEED_HELPER_NAME }
                val handler = menu.methods.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(Any::toString) == listOf(OPTION) && it.returnType == "V" }
                val handled = context.method(menu.type, handler.name, listOf(OPTION)).code()
                assertEquals("${bundle.name}: the handler's first call", SAVE_VIDEO,
                    handled.first { it.opcode == Opcode.INVOKE_STATIC && it.referenceText()?.startsWith("Lapp/hushgram/") == true }.referenceText())
                val builders = classes.flatMap { it.methods }.filter { method ->
                    context.method(method.definingClass, method.name, method.parameterTypes.map(Any::toString)).code().any { it.referenceText() == OFFER_VIDEO }
                }
                assertEquals("${bundle.name}: the builders offering Download", 1, builders.size)
                val code = context.method(builders.single().definingClass, builders.single().name, builders.single().parameterTypes.map(Any::toString)).code()
                assertEquals("${bundle.name}: offer() calls", 1, code.count { it.referenceText() == OFFER_VIDEO })
                assertTrue("${bundle.name}: no flag filtered", code.any { it.referenceText() == WITHHOLD_VIDEO })
                val offer = code.indexOfFirst { it.referenceText() == OFFER_VIDEO }
                assertEquals("${bundle.name}: offer() follows the check's answer", Opcode.MOVE_RESULT, code[offer - 1].opcode)
                val bridges = context.classDefBy(INSTAGRAM_MEDIA).methods.filter { it.name in videoBridges }
                assertEquals("${bundle.name}: the video bridges", videoBridges.size, bridges.size)
                bridges.forEach { assertEquals("${bundle.name}: ${it.name}", Opcode.CHECK_CAST, it.code().first().opcode) }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private val videoBridges = setOf(
        "videoVersions", "dashManifest", "mediaId", "owner", "takenAt", "username", "versionUrl", "versionWidth", "versionHeight",
    )

    private fun assertUntouched(context: BytecodePatchContext) {
        assertTrue("the builder changed", context.method(lambda, "invoke").code().none { it.referenceText()?.startsWith("Lapp/hushgram/") == true })
        assertEquals("the handler changed", Opcode.CONST_STRING, context.method(helper, "A09").code().first().opcode)
        assertEquals("a bridge was written", Opcode.CONST_4, context.method(INSTAGRAM_MEDIA, "videoVersions").code().first().opcode)
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>? = null): Method =
        classDefBy(type).methods.single { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** The index [branch] at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    private fun classes(name: String = FEED_HELPER_NAME, flagJumps: String = "if-eqz", handlerRegisters: Int = 42): List<ClassDef> {
        val menu = ImmutableClassDef(
            helper, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            listOf(
                ImmutableField(helper, "__redex_internal_original_name", "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, ImmutableStringEncodedValue(name), null, null),
                field(helper, "activity", activity),
                field(helper, "post", MEDIA),
            ),
            listOf(
                method(helper, "A09", listOf(OPTION), "V", handlerRegisters, static = false, body = """
                    const-string v0, "feed_action_sheet"
                    return-void
                """),
                // The getter that throws on null, by calling the one that reads the field.
                method(helper, "A00", listOf(helper), "$MEDIA", 2, static = true, body = """
                    invoke-static { p0 }, $helper->A01($helper)$MEDIA
                    move-result-object v0
                    return-object v0
                """),
                method(helper, "A01", listOf(helper), "$MEDIA", 2, static = true, body = """
                    iget-object v0, p0, $helper->post:$MEDIA
                    return-object v0
                """),
            ),
        )
        // The feed menu's builder, one case of a merged lambda: the row is out of line, reached by
        // jumps when the field's flag or the server flag is off.
        val builder = classDef(lambda, listOf(method(lambda, "invoke", emptyList(), "Ljava/lang/Object;", 10, static = false, body = """
            const/4 v0, 0x0
            iget-object v4, p0, $lambda->state:Lfixture/State;
            iget-object v3, v4, Lfixture/State;->media:$MEDIA
            const/4 v2, 0x0
            const/4 v1, 0x0
            invoke-virtual { v1, v2, v3 }, $check
            move-result v1
            if-eqz v1, :skip
            iget-boolean v1, v4, Lfixture/State;->flagged:Z
            if-eqz v1, :row
            const-wide v5, 0x81034200060c62L
            invoke-static { v2, v5, v6 }, $flag
            move-result v1
            $flagJumps v1, :row
            :skip
            invoke-static { v0 }, $helper->A01($helper)$MEDIA
            move-result-object v0
            return-object v0
            :row
            sget-object v7, $DOWNLOAD
            goto :skip
        """)))
        val eligible = classDef(util, listOf(method(util, "A08", listOf(session, MEDIA), "Z", 4, static = false, body = """
            const-string v0, "android_purge_26_q3_$ELIGIBLE_MARKER"
            const/4 v0, 0x0
            return v0
        """)))
        val mediaGetters = listOf(
            "AAh" to ("video_versions" to "Ljava/util/List;"),
            "A8P" to ("video_dash_manifest" to "Ljava/lang/String;"),
            "A3Q" to ("user" to USER),
            "A6v" to ("taken_at" to "Ljava/lang/Long;"),
        ).map { (name, field) -> getter(MEDIA, name, field.first, field.second) } +
            method(MEDIA, "getId", emptyList(), "Ljava/lang/String;", 1, static = false, body = """
                const/4 v0, 0x0
                return-object v0
            """)
        val versionGetters = listOf("getUrl" to ("url" to "Ljava/lang/String;"), "DvO" to ("width" to "Ljava/lang/Integer;"),
            "CK7" to ("height" to "Ljava/lang/Integer;"))
        return listOf(
            menu, builder, eligible,
            classDef(MEDIA, mediaGetters),
            classDef(USER, listOf(getter(USER, "A89", "username", "Ljava/lang/String;"))),
            ImmutableClassDef(
                VIDEO_VERSION, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
                "Ljava/lang/Object;", null, null, null, null,
                versionGetters.map { (name, field) ->
                    ImmutableMethod(VIDEO_VERSION, name, emptyList(), field.second, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
                },
            ),
            classDef(PANDO_VIDEO_VERSION, versionGetters.map { (name, field) -> getter(PANDO_VIDEO_VERSION, name, field.first, field.second) }),
            ExtensionDex.classDef(INSTAGRAM_MEDIA),
        )
    }

    private fun getter(owner: String, name: String, field: String, returns: String) =
        method(owner, name, emptyList(), returns, 2, static = false, body = """
            const v0, ${field.hashCode()}
            const/4 v0, 0x0
            return-object v0
        """)

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, static: Boolean, body: String): Method {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun field(owner: String, name: String, type: String) =
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods)
}
