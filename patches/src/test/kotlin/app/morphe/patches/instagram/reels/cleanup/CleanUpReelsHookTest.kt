/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanUpReelsHookTest {
    private val scope = "Lfixture/ComponentScope;"
    private val component = "Lfixture/Component;"

    /** The hooks the patch writes are in the ReelDeclutter the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(HIDE_FOLLOW_BUTTON, HIDE_CHIPS, HIDE_SOCIAL_FOOTER)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** Each part's method asks its hook first; the Legacy Follow button of the suggested accounts' cards stays. */
    @Test
    fun eachPartAsksItsHookFirst() {
        val context = PatchContexts.of(classes())

        context.hideReelParts()

        REEL_PARTS.forEach { part ->
            val method = context.mutableClassDefBy(owner(part.marker)).methods.single()
            assertGuardFirst(part.marker, part, method)
        }
        val legacy = context.mutableClassDefBy(owner("ClipsFollowButtonComponentLegacy_render")).methods.single()
        assertEquals("the Legacy Follow button was touched", 3, legacy.instructions().size)
    }

    @Test
    fun aMissingPartFailsThePatch() {
        val context = PatchContexts.of(classes(leaveOut = "ClipsMetaAiPillComponent_render"))
        val failure = assertThrows(PatchException::class.java) { context.hideReelParts() }
        assertTrue(failure.message!!, failure.message!!.contains("ClipsMetaAiPillComponent_render"))
    }

    @Test
    fun aMarkerInTwoMethodsFailsThePatch() {
        val copy = render("Lfixture/Copy;", "ClipsFollowButtonComponent_render")
        val context = PatchContexts.of(classes() + copy)
        assertThrows(PatchException::class.java) { context.hideReelParts() }
    }

    @Test
    fun aRenderOfAnotherShapeFailsThePatch() {
        val context = PatchContexts.of(classes(staticRender = "ClipsFriendlyViewerComponent_render"))
        assertThrows(PatchException::class.java) { context.hideReelParts() }
    }

    @Test
    fun aCheckThatIsNotABooleanFailsThePatch() {
        val context = PatchContexts.of(classes(checkReturns = "I"))
        assertThrows(PatchException::class.java) { context.hideReelParts() }
    }

    /** In each declared build every part is one method of its shape, and each gets its hook first. */
    @Test
    fun eachDeclaredBuildHidesEveryPart() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val markers = REEL_PARTS.map { it.marker }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    val holds = dex.stringSection.any { string ->
                        string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1) in markers
                    }
                    if (!holds) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.markers().any { it in markers } }) {
                            classes += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val context = PatchContexts.of(classes)

                context.hideReelParts()

                REEL_PARTS.forEach { part ->
                    val before = classes.flatMap { it.methods }.single { part.marker in it.markers() }
                    val after = context.mutableClassDefBy(before.definingClass).methods.single {
                        it.name == before.name && it.parameterTypes.map(Any::toString) == before.parameterTypes.map(Any::toString)
                    }
                    assertEquals("${bundle.name}: ${part.marker} size", before.instructions().size + 5, after.instructions().size)
                    assertGuardFirst("${bundle.name}: ${part.marker}", part, after)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertGuardFirst(what: String, part: ReelPart, method: Method) {
        val code = method.instructions()
        val answer = if (part.check) Opcode.RETURN else Opcode.RETURN_OBJECT
        assertEquals(
            "$what: the guard's opcodes",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, answer),
            code.take(5).map { it.opcode },
        )
        assertEquals("$what: the hook called", part.hook, (code[0] as ReferenceInstruction).reference.toString())
        val jump = code[2] as OffsetInstruction
        assertEquals("$what: the branch lands on the method's own code", code.subList(2, 5).sumOf { it.codeUnits }, jump.codeOffset)
    }

    private fun owner(marker: String) = "Lfixture/${marker.substringBefore('_')};"

    /**
     * Stand-ins shaped like Instagram 449's: a render per part and the Legacy Follow button's, each
     * the one method of its class loading its marker first, and the bubbles check, a static method
     * taking three objects.
     */
    private fun classes(leaveOut: String? = null, staticRender: String? = null, checkReturns: String = "Z"): List<ClassDef> {
        val renders = (REEL_PARTS.filter { !it.check }.map { it.marker } + "ClipsFollowButtonComponentLegacy_render")
            .filter { it != leaveOut }
            .map { render(owner(it), it, static = it == staticRender) }
        val checks = REEL_PARTS.filter { it.check && it.marker != leaveOut }.map { check(owner(it.marker), it.marker, checkReturns) }
        return renders + checks
    }

    private fun render(type: String, marker: String, static: Boolean = false): ClassDef {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Lfixture/KComponent;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    type, "A0m", listOf(ImmutableMethodParameter(scope, null, null)), component, flags, null, null,
                    ImmutableMethodImplementation(
                        3,
                        listOf(
                            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("android_purge_26_q3_$marker")),
                            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ),
                        null, null,
                    ),
                ),
            ),
        )
    }

    private fun check(type: String, marker: String, returns: String): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "A01", listOf("Lfixture/A;", "Lfixture/B;", "Lfixture/C;").map { ImmutableMethodParameter(it, null, null) },
                returns, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(
                    5,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("android_purge_26_q3_$marker")),
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                        ImmutableInstruction11x(Opcode.RETURN, 0),
                    ),
                    null, null,
                ),
            ),
        ),
    )

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
}
