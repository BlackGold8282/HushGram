/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns
import app.morphe.patches.instagram.feed.FeedItemStandIns.assertFilteredBeforeReturn
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.instagram.feed.filterParsedFeedItems
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideMetaAiHookTest {
    /** Both hooks the patch writes are in the MetaAi the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(SEARCH_FLAG, META_AI_FILTER)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** Each read, through the config interface or a static wrapper, answers through the extension. */
    @Test
    fun everyReadOfASearchFlagAnswersThroughTheExtension() {
        val context = PatchContexts.of(
            listOf(gate(SEARCH, SEARCH_FLAGS[0]), wrapped(INBOX, SEARCH_FLAGS[1]), gate(RING, SEARCH_FLAGS[2])),
        )

        val reads = context.findSearchFlagReads()
        context.answerSearchFlagReads(reads)

        assertEquals(3, reads.size)
        for (type in listOf(SEARCH, INBOX, RING)) {
            assertAnsweredAfterEveryRead(type, context.mutableClassDefBy(type).methods.single { it.name == "A00" })
        }
    }

    @Test
    fun aFlagNobodyReadsFailsThePatch() {
        val context = PatchContexts.of(listOf(gate(SEARCH, SEARCH_FLAGS[0]), gate(RING, SEARCH_FLAGS[2])))
        assertThrows(PatchException::class.java) { context.findSearchFlagReads() }
    }

    /** A flag's id going anywhere but a boolean read is an update the patch hasn't seen. */
    @Test
    fun aFlagReadAsSomethingElseFailsThePatch() {
        val context = PatchContexts.of(
            listOf(
                gate(SEARCH, SEARCH_FLAGS[0]), gate(RING, SEARCH_FLAGS[2]),
                gate(INBOX, SEARCH_FLAGS[1], returns = "J", result = Opcode.MOVE_RESULT_WIDE),
            ),
        )
        assertThrows(PatchException::class.java) { context.findSearchFlagReads() }
    }

    @Test
    fun aFlagReadWithItsAnswerDroppedFailsThePatch() {
        val context = PatchContexts.of(
            listOf(gate(SEARCH, SEARCH_FLAGS[0]), gate(RING, SEARCH_FLAGS[2]), gate(INBOX, SEARCH_FLAGS[1], result = null)),
        )
        assertThrows(PatchException::class.java) { context.findSearchFlagReads() }
    }

    /** The extension's own code may name the flags; only the app's reads are answered. */
    @Test
    fun theExtensionIsLeftAlone() {
        val own = gate("Lapp/hushgram/extension/instagram/metaai/Probe;", SEARCH_FLAGS[0])
        val context = PatchContexts.of(
            listOf(own, gate(SEARCH, SEARCH_FLAGS[0]), wrapped(INBOX, SEARCH_FLAGS[1]), gate(RING, SEARCH_FLAGS[2])),
        )

        val reads = context.findSearchFlagReads()

        assertEquals(setOf(SEARCH, INBOX, RING), reads.map { it.type }.toSet())
        assertEquals(3, reads.size)
    }

    @Test
    fun theFeedParseHelperAnswersThroughTheMetaAiFilter() {
        val context = PatchContexts.of(FeedItemStandIns.classes(listOf("MEDIA", "AD", "CLIPS_NETEGO") + META_AI_UNITS))

        context.filterParsedFeedItems("Hide Meta AI", META_AI_FILTER, META_AI_UNITS)

        val helper = context.mutableClassDefBy(FeedItemStandIns.ITEM).methods.single { it.name == "A02" }
        assertFilteredBeforeReturn("helper", helper, META_AI_FILTER)
    }

    @Test
    fun aKindEnumMissingAMetaAiUnitFailsThePatch() {
        val context = PatchContexts.of(FeedItemStandIns.classes(listOf("MEDIA", "AD") + META_AI_UNITS.drop(1)))
        assertThrows(PatchException::class.java) {
            context.filterParsedFeedItems("Hide Meta AI", META_AI_FILTER, META_AI_UNITS)
        }
    }

    /**
     * In each declared build every flag is read, every read is a boolean whose answer is kept, and
     * after the patch each answer goes through the extension. On 449 that's five reads.
     */
    @Test
    fun eachDeclaredBuildAnswersEveryReadOfEveryFlag() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.loadsAFlag() }) holders += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(holders)

                val reads = context.findSearchFlagReads()
                context.answerSearchFlagReads(reads)

                assertEquals("${bundle.name}: flags read", SEARCH_FLAGS.toSet(), reads.map { it.flag }.toSet())
                if (version == "449.0.0.52.84") assertEquals("${bundle.name}: reads", 5, reads.size)
                for (read in reads) {
                    val method = context.mutableClassDefBy(read.type).methods.single {
                        it.name == read.name && it.parameterTypes.map(CharSequence::toString) == read.parameters
                    }
                    assertAnsweredAfterEveryRead("${bundle.name} ${read.type}->${read.name}", method)
                }
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun Method.loadsAFlag(): Boolean = implementation?.instructions?.any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in SEARCH_FLAGS
    } == true

    /** After each flag load, its read's move-result is followed by the extension call and a move-result into the same register. */
    private fun assertAnsweredAfterEveryRead(what: String, method: Method) {
        val code = method.instructions()
        val loads = code.indices.filter { code[it].opcode == Opcode.CONST_WIDE && (code[it] as WideLiteralInstruction).wideLiteral in SEARCH_FLAGS }
        assertTrue("$what loads no flag", loads.isNotEmpty())
        for (load in loads) {
            val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
            val register = (code[result] as OneRegisterInstruction).registerA
            val call: Instruction = code[result + 1]
            assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$what: the hook", SEARCH_FLAG, (call as ReferenceInstruction).reference.toString())
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
            assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
        }
    }

    private companion object {
        const val SEARCH = "Lfixture/SearchGate;"
        const val INBOX = "Lfixture/InboxScreen;"
        const val RING = "Lfixture/InboxRingGate;"
        const val CONFIG = "Lfixture/MobileConfig;"
        const val SESSION = "Lfixture/UserSession;"

        /** Shaped like 449's search gate: the id, a cast of the config, and the interface's boolean read. */
        fun gate(type: String, flag: Long, returns: String = "Z", result: Opcode? = Opcode.MOVE_RESULT) = holder(
            type,
            listOfNotNull(
                ImmutableInstruction51l(Opcode.CONST_WIDE, 0, flag),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference(CONFIG)),
                ImmutableInstruction35c(
                    Opcode.INVOKE_INTERFACE, 3, 2, 0, 1, 0, 0, ImmutableMethodReference(CONFIG, "BXd", listOf("J"), returns),
                ),
                result?.let { ImmutableInstruction11x(it, 0) },
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
        )

        /** Shaped like 449's inbox: the id passed to a static wrapper after the config. */
        fun wrapped(type: String, flag: Long) = holder(
            type,
            listOf(
                ImmutableInstruction51l(Opcode.CONST_WIDE, 0, flag),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 3, 2, 0, 1, 0, 0,
                    ImmutableMethodReference("Lfixture/Config;", "A1A", listOf("Ljava/lang/Object;", "J"), "Z"),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
        )

        fun holder(type: String, code: List<Instruction>): ClassDef {
            val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "A00", listOf(ImmutableMethodParameter(SESSION, null, null)), "Z", flags, null, null,
                        ImmutableMethodImplementation(3, code, null, null),
                    ),
                ),
            )
        }
    }
}
