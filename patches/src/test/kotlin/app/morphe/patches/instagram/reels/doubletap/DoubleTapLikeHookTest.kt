/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.doubletap

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapLikeHookTest {
    private val feed = "Lfixture/MediaHolderGestureDelegate;"
    private val handler = "Lfixture/GestureActionHandler;"
    private val action = "Lfixture/LikeAction;"
    private val handleMarker = "android_purge_26_q3_$HANDLE_DOUBLE_TAP"
    private val setterMarker = "android_purge_26_q3_$SET_LIKE_ACTION"

    /** The hooks the patch writes are in the DoubleTapLike the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(HOLD_BACK_POST, LIKE_ACTION)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** The feed's double tap asks first, and the Reels handler's like action is asked for as it's read. */
    @Test
    fun bothDoubleTapsAsk() {
        val context = PatchContexts.of(classes())

        context.turnOffDoubleTapLikes()

        assertGuardFirst("the feed double tap", context.mutableClassDefBy(feed).methods.single { it.name == "onDoubleTap" }.code())
        val reel = context.mutableClassDefBy(handler).methods.single { it.name == "handleDoubleTap" }.code()
        assertAskedAfterRead("the Reels double tap", reel, "$handler->likeAction:$action")
        assertTrue(
            "the setter was touched",
            context.mutableClassDefBy(handler).methods.single { it.name == "setLikeAction" }.code().none { it.referenceText() == LIKE_ACTION },
        )
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(feeds = 0) to "found 0",
            classes(feeds = 2) to "found 2",
            classes(handlers = 0) to "marked $HANDLE_DOUBLE_TAP, found 0",
            classes(setterElsewhere = true) to "isn't in $handler",
            classes(setterWrites = 2) to "writes 2 fields",
            classes(reads = 2) to "2 times",
            classes(checkedLater = true) to "doesn't check its like action for null straight after",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.turnOffDoubleTapLikes() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.classDefByOrNull(type)?.methods?.toList().orEmpty() }
                .filter { method -> method.code().any { it.referenceText() == HOLD_BACK_POST || it.referenceText() == LIKE_ACTION } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build both double taps are found and hooked. */
    @Test
    fun eachDeclaredBuildGetsBothHooks() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = (FixtureDex.classesHolding(bundle, FEED_DOUBLE_TAP) + FixtureDex.classesHolding(bundle, handleMarker))
                    .distinctBy { it.type }
                val context = PatchContexts.of(holders)
                val postType = holders.single { holder -> holder.methods.any { method -> method.code().any { it.string() == FEED_DOUBLE_TAP } } }.type
                val reelType = holders.single { holder -> holder.methods.any { method -> method.code().any { it.string() == handleMarker } } }.type

                context.turnOffDoubleTapLikes()

                val posts = context.mutableClassDefBy(postType).methods.filter { method -> method.code().any { it.referenceText() == HOLD_BACK_POST } }
                assertEquals("${bundle.name}: one feed double tap asks", 1, posts.size)
                assertGuardFirst("${bundle.name}: the feed double tap", posts.single().code())
                val reels = context.mutableClassDefBy(reelType).methods.filter { method -> method.code().any { it.referenceText() == LIKE_ACTION } }
                assertEquals("${bundle.name}: one Reels double tap asks", 1, reels.size)
                val reel = reels.single()
                assertTrue("${bundle.name}: the Reels double tap", handleMarker in reel.code().mapNotNull { it.string() })
                val asked = reel.code().indexOfFirst { it.referenceText() == LIKE_ACTION }
                val read = reel.code()[asked - 1]
                assertEquals("${bundle.name}: what comes before the ask", Opcode.IGET_OBJECT, read.opcode)
                assertAskedAfterRead("${bundle.name}: the Reels double tap", reel.code(), read.referenceText()!!)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertGuardFirst(what: String, code: List<Instruction>) {
        assertEquals(
            "$what: the guard's opcodes",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
            code.take(4).map { it.opcode },
        )
        assertEquals("$what: the hook called", HOLD_BACK_POST, code[0].referenceText())
    }

    /** The one read of [field] is followed by the ask, its answer back in the same register, a cast, then the null check. */
    private fun assertAskedAfterRead(what: String, code: List<Instruction>, field: String) {
        val reads = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && code[it].referenceText() == field }
        assertEquals("$what: reads of the like action", 1, reads.size)
        val read = reads.single()
        val register = (code[read] as TwoRegisterInstruction).registerA
        val ask = code[read + 1] as RegisterRangeInstruction
        assertEquals("$what: the ask", LIKE_ACTION, code[read + 1].referenceText())
        assertEquals("$what: the ask hands over the read", listOf(register, 1), listOf(ask.startRegister, ask.registerCount))
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[read + 2].opcode)
        assertEquals("$what: the answer's register", register, (code[read + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[read + 3].opcode)
        assertEquals("$what: the cast's type", field.substringAfter(":"), ((code[read + 3] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$what: the null check follows", Opcode.IF_EQZ, code[read + 4].opcode)
        assertEquals("$what: the null check's register", register, (code[read + 4] as OneRegisterInstruction).registerA)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The feed's gesture delegate, whose onDoubleTapMedia files its report without an activity, and
     * the Reels gesture handler: its double tap reads the like action and skips the like without
     * one, and its setter stores that action.
     */
    private fun classes(
        feeds: Int = 1,
        handlers: Int = 1,
        setterElsewhere: Boolean = false,
        setterWrites: Int = 1,
        reads: Int = 1,
        checkedLater: Boolean = false,
    ): List<ClassDef> {
        val feedClass = classDef(
            feed,
            (0 until feeds).map { copy ->
                method(feed, if (copy == 0) "onDoubleTap" else "onDoubleTapAgain", listOf("Ljava/lang/Object;"), "V", 3, """
                    const-string v0, "$FEED_DOUBLE_TAP"
                    return-void
                """)
            },
        )
        val readAndCheck = if (checkedLater) {
            """
                iget-object v1, p0, $handler->likeAction:$action
                const/4 v0, 0x0
                if-eqz v1, :done
            """
        } else {
            """
                iget-object v1, p0, $handler->likeAction:$action
                if-eqz v1, :done
            """
        }
        val secondRead = if (reads > 1) "iget-object v0, p0, $handler->likeAction:$action" else ""
        val handle = (0 until handlers).map { copy ->
            method(handler, if (copy == 0) "handleDoubleTap" else "handleDoubleTapAgain", emptyList(), "V", 3, """
                const-string v0, "$handleMarker"
                $secondRead
                $readAndCheck
                invoke-interface { v1 }, $action->invoke()V
                :done
                return-void
            """)
        }
        val setterBody = """
            const-string v0, "$setterMarker"
            iput-object p1, p0, $handler->likeAction:$action
            ${if (setterWrites > 1) "iput-object p1, p0, $handler->otherLikeAction:$action" else ""}
            return-void
        """
        val setterOwner = if (setterElsewhere) "Lfixture/OtherHandler;" else handler
        val setter = method(setterOwner, "setLikeAction", listOf(action), "V", 3, setterBody)
        val handlerClass = classDef(handler, handle + (if (setterElsewhere) emptyList() else listOf(setter)))
        return listOfNotNull(feedClass, handlerClass, if (setterElsewhere) classDef(setterOwner, listOf(setter)) else null)
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String): Method {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        if (it is FieldReference) "${it.definingClass}->${it.name}:${it.type}" else it.toString()
    }

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
