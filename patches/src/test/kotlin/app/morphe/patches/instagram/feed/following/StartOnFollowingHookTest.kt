/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.following

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.flags.answerFlagReads
import app.morphe.patches.instagram.misc.flags.findFlagReads
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartOnFollowingHookTest {
    /** Both hooks the patch writes are in the FollowingFeed the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(FEED_FLAG, SAVED_FEED)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun theSavedFeedsReturnIsFound() {
        val context = PatchContexts.of(listOf(savedFeed()))

        val found = context.findSavedFeedReturn()

        assertEquals(SAVED, found.type)
        assertEquals("A01", found.name)
        assertEquals(8, found.returnAt)
        assertEquals(0, found.register)
    }

    /** The saved name goes past the extension on its way out, and both flags' reads answer through it too. */
    @Test
    fun theSavedFeedAndBothFlagsAnswerThroughTheExtension() {
        val context = PatchContexts.of(listOf(savedFeed(), pickerGate()))

        val reads = context.findFlagReads("test", FEED_PICKER_FLAGS)
        context.defaultSavedFeed(context.findSavedFeedReturn())
        context.answerFlagReads(reads, FEED_FLAG)

        assertEquals(FEED_PICKER_FLAGS.toSet(), reads.map { it.flag }.toSet())
        assertDefaultedAndAnswered("stand-in", context.mutableClassDefBy(SAVED).methods.single { it.name == "A01" })
        assertAnsweredAfterEveryRead("picker", context.mutableClassDefBy(PICKER).methods.single())
    }

    /** Without the For you flag's read, the picker would come up without For you, so that's an update the patch hasn't seen. */
    @Test
    fun aPickerFlagNobodyReadsFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed()))
        assertThrows(PatchException::class.java) { context.findFlagReads("test", FEED_PICKER_FLAGS) }
    }

    @Test
    fun noClassKeepingTheSavedFeedFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(key = "last_selected_tab")))
        assertThrows(PatchException::class.java) { context.findSavedFeedReturn() }
    }

    @Test
    fun twoGettersFailThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(getters = 2)))
        assertThrows(PatchException::class.java) { context.findSavedFeedReturn() }
    }

    /** A getter that no longer asks the flag is an update the patch hasn't seen. */
    @Test
    fun aGetterWithoutTheFlagFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(flag = FOR_YOU_PICKER_FLAG)))
        assertThrows(PatchException::class.java) { context.findSavedFeedReturn() }
    }

    @Test
    fun aGetterReturningItUncastFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(cast = false)))
        assertThrows(PatchException::class.java) { context.findSavedFeedReturn() }
    }

    @Test
    fun theExtensionIsLeftAlone() {
        val own = savedFeed(type = "Lapp/hushgram/extension/instagram/feed/Probe;")
        val context = PatchContexts.of(listOf(own, savedFeed()))

        assertEquals(SAVED, context.findSavedFeedReturn().type)
    }

    /**
     * In each declared build every read of both flags and the saved feed's getter are found, and
     * after the patch each answer and the saved name go through the extension. On 449 that's twelve
     * reads of the remembered feed flag, three of the For you one, and LX/00lX.
     */
    @Test
    fun eachDeclaredBuildStartsHomeOnFollowing() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.loadsTheFlag() || it.holds(SAVED_FEED_KEY) }) holders += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(holders)

                val reads = context.findFlagReads("test", FEED_PICKER_FLAGS)
                val saved = context.findSavedFeedReturn()
                context.defaultSavedFeed(saved)
                context.answerFlagReads(reads, FEED_FLAG)

                if (version == "449.0.0.52.84") {
                    assertEquals("${bundle.name}: remembered feed reads", 12, reads.count { it.flag == REMEMBERED_FEED_FLAG })
                    assertEquals("${bundle.name}: For you reads", 3, reads.count { it.flag == FOR_YOU_PICKER_FLAG })
                }
                assertDefaultedAndAnswered(
                    "${bundle.name} ${saved.type}",
                    context.mutableClassDefBy(saved.type).methods.single { it.name == saved.name && it.parameterTypes.isEmpty() },
                )
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

    private fun Method.loadsTheFlag(): Boolean = implementation?.instructions?.any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in FEED_PICKER_FLAGS
    } == true

    private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    /** The getter's String cast is followed by the extension call, the answer back in its register and the return; one call in all. */
    private fun assertDefaultedAndAnswered(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == SAVED_FEED }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[hook - 1].opcode)
        val register = (code[hook - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the return", Opcode.RETURN_OBJECT, code[hook + 2].opcode)
        assertEquals("$what: the returned register", register, (code[hook + 2] as OneRegisterInstruction).registerA)
        assertAnsweredAfterEveryRead(what, method)
    }

    /** After each flag load, its read's move-result is followed by the extension call and a move-result into the same register. */
    private fun assertAnsweredAfterEveryRead(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val loads = code.indices.filter {
            code[it].opcode == Opcode.CONST_WIDE && (code[it] as WideLiteralInstruction).wideLiteral in FEED_PICKER_FLAGS
        }
        assertTrue("$what loads no flag", loads.isNotEmpty())
        for (load in loads) {
            val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
            val register = (code[result] as OneRegisterInstruction).registerA
            val call: Instruction = code[result + 1]
            assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$what: the hook", FEED_FLAG, (call as ReferenceInstruction).reference.toString())
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
            assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
        }
    }

    private companion object {
        const val SAVED = "Lfixture/SavedFeedType;"
        const val PICKER = "Lfixture/FeedTitle;"
        const val CONFIG = "Lfixture/MobileConfig;"
        const val STORE = "Lfixture/Store;"

        /**
         * Shaped like 449's LX/00lX: a constructor naming the preference, and a getter that asks
         * the flag, then returns the stored name cast to a String, or null without the flag.
         */
        fun savedFeed(
            type: String = SAVED,
            key: String = SAVED_FEED_KEY,
            flag: Long = REMEMBERED_FEED_FLAG,
            getters: Int = 1,
            cast: Boolean = true,
        ): ClassDef {
            val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val constructor = ImmutableMethod(
                type, "<init>", null, "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(
                    2,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key)),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            )
            val code = listOfNotNull<Instruction>(
                ImmutableInstruction51l(Opcode.CONST_WIDE, 0, flag),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference(CONFIG)),
                ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 3, 2, 0, 1, 0, 0, ImmutableMethodReference(CONFIG, "BXd", listOf("J"), "Z")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, if (cast) 9 else 7),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ImmutableMethodReference(STORE, "read", null, "Ljava/lang/Object;")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                if (cast) ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference("Ljava/lang/String;")) else null,
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
            val methods = (1..getters).map { n ->
                ImmutableMethod(
                    type, "A0$n", null, "Ljava/lang/String;", instance, null, null,
                    ImmutableMethodImplementation(4, code, null, null),
                )
            }
            return ImmutableClassDef(
                type, instance, "Ljava/lang/Object;", null, null, null, null, listOf(constructor) + methods,
            )
        }

        /** Shaped like 449's title setup: the For you flag's read, and a return when it's off. */
        fun pickerGate(): ClassDef {
            val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val code = listOf<Instruction>(
                ImmutableInstruction51l(Opcode.CONST_WIDE, 0, FOR_YOU_PICKER_FLAG),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference(CONFIG)),
                ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 3, 2, 0, 1, 0, 0, ImmutableMethodReference(CONFIG, "BXd", listOf("J"), "Z")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            return ImmutableClassDef(
                PICKER, instance, "Ljava/lang/Object;", null, null, null, null,
                listOf(ImmutableMethod(PICKER, "A09", null, "V", instance, null, null, ImmutableMethodImplementation(4, code, null, null))),
            )
        }
    }
}
