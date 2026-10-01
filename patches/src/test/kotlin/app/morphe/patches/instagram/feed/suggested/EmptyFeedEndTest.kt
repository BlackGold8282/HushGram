/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyFeedEndTest {
    private val adapter = "Lfixture/MainFeedAdapter;"
    private val feed = "Lfixture/Feed;"
    private val ended = "$feed->ended:Z"

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_ENDED.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_ENDED is not in the extension: $declared", FEED_ENDED.substringAfter("->") in declared)
    }

    /**
     * The flag is the last boolean read before the loading row, not an earlier one, and every read
     * of it in the adapter goes past the extension, the one in its empty check too.
     */
    @Test
    fun everyReadOfTheFlagGoesPastTheExtension() {
        val context = PatchContexts.of(listOf(adapter()))

        val end = context.findFeedEnd()
        assertEquals(adapter, end.adapter)
        assertEquals(ended, end.flag.toString())
        context.endEmptiedFeed(end)

        for (name in listOf("buildModels", "isEmptyFeed")) {
            val code = context.code(name)
            val read = code.indexOfFirst { it.reads(ended) }
            val hook = code[read + 1]
            assertTrue("$name: right after the read", hook.calls(FEED_ENDED))
            val flag = (code[read] as TwoRegisterInstruction).registerA
            assertEquals("$name: the flag", flag, (hook as RegisterRangeInstruction).startRegister)
            assertEquals(Opcode.MOVE_RESULT, code[read + 2].opcode)
            assertEquals(flag, (code[read + 2] as OneRegisterInstruction).registerA)
            assertEquals("$name: hooked once", 1, code.count { it.calls(FEED_ENDED) })
        }
        val other = context.code("buildModels").indexOfFirst { it.reads("$feed->other:Z") }
        assertTrue("the other flag is left", !context.code("buildModels")[other + 1].calls(FEED_ENDED))
    }

    @Test
    fun aBuilderThatDoesntAskWhetherTheFeedIsEmptyFailsThePatch() {
        val context = PatchContexts.of(listOf(adapter(asks = false)))
        assertThrows(PatchException::class.java) { context.findFeedEnd() }
    }

    @Test
    fun twoBuildersFailThePatch() {
        val context = PatchContexts.of(listOf(adapter(), adapter("Lfixture/SecondAdapter;")))
        assertThrows(PatchException::class.java) { context.findFeedEnd() }
    }

    /**
     * In each declared build the home feed adapter reads its flag twice, in its model builder and its
     * empty check, and both reads go past the extension. On 449 that's LX/00pR reading LX/00pT;->A02:Z
     * in A16 and A1F.
     */
    @Test
    fun eachDeclaredBuildEndsTheEmptiedFeed() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.holds(BUILD_MODELS) }) classes += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(classes.distinctBy { it.type })

                val end = context.findFeedEnd()
                context.endEmptiedFeed(end)

                val reads = context.classDefBy(end.adapter).methods.flatMap { method ->
                    val code = method.implementation?.instructions?.toList().orEmpty()
                    code.indices.filter { code[it].reads(end.flag.toString()) }.map { code to it }
                }
                assertEquals("${bundle.name}: ${end.adapter} reads ${end.flag}", 2, reads.size)
                for ((code, at) in reads) {
                    assertTrue("${bundle.name}: right after the read", code[at + 1].calls(FEED_ENDED))
                    assertEquals(Opcode.MOVE_RESULT, code[at + 2].opcode)
                }
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun Instruction.calls(reference: String) =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

    private fun Instruction.reads(reference: String) =
        ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == reference

    private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    private fun BytecodePatchContext.code(name: String): List<Instruction> =
        classDefBy(adapter).methods.single { it.name == name }.implementation!!.instructions.toList()

    /**
     * The builder reads another flag first, then the one that ends the feed, asks the feed whether
     * it's empty, and asks again before adding the loading row. Its empty check reads the flag too.
     */
    private fun adapter(type: String = adapter, asks: Boolean = true) = classDef(type, listOf(
        method(type, "buildModels", "V", registers = 4, body = """
            const-string v0, "$BUILD_MODELS"
            iget-object v1, v3, $type->feed:$feed
            iget-boolean v2, v1, $feed->other:Z
            iget-boolean v0, v1, $ended
            if-eqz v0, :loading
            ${if (asks) "invoke-virtual { v1 }, $feed->isEmpty()Z\nmove-result v0" else "const/4 v0, 0x1"}
            if-eqz v0, :loading
            return-void
            :loading
            invoke-virtual { v1 }, $feed->isEmpty()Z
            move-result v0
            if-eqz v0, :done
            const-string v0, "$SHIMMER_KEY"
            :done
            return-void
        """),
        method(type, "isEmptyFeed", "Z", registers = 3, body = """
            iget-object v1, v2, $type->feed:$feed
            iget-boolean v0, v1, $ended
            return v0
        """),
    ))

    private fun method(type: String, name: String, returnType: String, registers: Int, body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                type, name, emptyList<ImmutableMethodParameter>(), returnType, AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)
}
