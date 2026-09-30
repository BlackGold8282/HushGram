/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.taptoplay

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
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TapToPlayHookTest {
    private val player = "Lfixture/VideoPlayer;"
    private val groot = "Lfixture/GrootPlayer;"
    private val core = "Lfixture/CorePlayer;"
    private val checker = "Lfixture/AutoplayChecker;"
    private val string = "Ljava/lang/String;"

    /** Every hook the patch writes is in the extension the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(ALLOW_START, ALLOW_DIRECT_START, PAUSED, REBOUND, AUTOPLAY_ALLOWED, TOUCH)) {
            val type = hook.substringBefore("->")
            val declared = ExtensionDex.classDef(type).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * playInternal hands over the IgGrootPlayer it would play and returns on a no, IgGrootPlayer's
     * play does the same, its pause and prepare report first thing, every return of the autoplay
     * check goes through the filter, and the touch dispatch feeds the tap clock.
     */
    @Test
    fun eachHookGoesFirst() {
        val context = PatchContexts.of(classes())

        context.holdStartsWithoutATap()

        assertGateFirst("playInternal", context.method(player, "A0J").code(), ALLOW_START, prefix = listOf(Opcode.IGET_OBJECT))
        val internal = context.method(player, "A0J").code()
        assertEquals("the IgGrootPlayer comes from the player's field", "$player->groot:$groot", (internal[0] as ReferenceInstruction).reference.toString())
        assertEquals("read from playInternal's player", 2, (internal[0] as TwoRegisterInstruction).registerB)

        val play = context.method(groot, "A0X").code()
        assertGateFirst("play", play, ALLOW_DIRECT_START)
        assertEquals("this and the reason", listOf(1, 2), (play[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })

        val pause = context.method(groot, "A0V").code()
        assertEquals(PAUSED, pause[0].referenceText())
        assertEquals("this and the reason", listOf(1, 2), (pause[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertTrue("the other String method", context.method(groot, "A0W").code().none { it.referenceText() == PAUSED })

        val prepare = context.method(groot, "A0S").code()
        assertEquals(REBOUND, prepare[0].referenceText())
        assertEquals("this, past v15", listOf(21, 1), (prepare[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })

        val check = context.method(checker, "A01").code()
        val returns = check.indices.filter { check[it].opcode == Opcode.RETURN }
        assertEquals(2, returns.size)
        for (at in returns) {
            assertEquals(AUTOPLAY_ALLOWED, check[at - 2].referenceText())
            assertEquals(Opcode.MOVE_RESULT, check[at - 1].opcode)
        }
        val branch = check.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals("the branch to the second return passes through the filter", returns[1] - 2, check.target(branch))

        val touch = context.method(FRAGMENT_ACTIVITY, "dispatchTouchEvent").code()
        assertEquals(TOUCH, touch[0].referenceText())
        assertEquals("the activity and the event", listOf(1, 2), (touch[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
    }

    /** A playInternal that reads its IgGrootPlayer from anything but its player can't be gated first thing. */
    @Test
    fun aGrootReadFromElsewhereFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(grootFromElsewhere = true))
        val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
        assertTrue(failure.message!!, failure.message!!.contains("other than its player"))
        assertUntouched(context)
    }

    @Test
    fun twoPausesFailBeforeAnythingChanges() {
        val context = PatchContexts.of(classes(secondPause = true))
        val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
        assertTrue(failure.message!!, failure.message!!.contains("pause"))
        assertUntouched(context)
    }

    @Test
    fun withoutTheActivityNothingChanges() {
        val context = PatchContexts.of(classes(activity = false))
        val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
        assertTrue(failure.message!!, failure.message!!.contains(FRAGMENT_ACTIVITY))
        assertUntouched(context)
    }

    /**
     * In each declared build, playInternal, IgGrootPlayer's play, pause and prepare, the autoplay
     * check and the touch dispatch are each found once, and each gets its hook first.
     */
    @Test
    fun eachDeclaredBuildGatesItsPlayers() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = (
                    FixtureDex.classesHolding(bundle, PLAY_INTERNAL) + FixtureDex.classesHolding(bundle, GROOT_PREPARE) +
                        FixtureDex.classesHolding(bundle, AUTOPLAY_CHECKER.last()) +
                        listOfNotNull(FixtureDex.classes(bundle, setOf(FRAGMENT_ACTIVITY))[FRAGMENT_ACTIVITY])
                    ).distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val hooks = context.findPlayerHooks()
                assertEquals("${bundle.name}: the player's IgGrootPlayer field", hooks.prepare.definingClass, hooks.grootField.type)
                assertEquals("${bundle.name}: play and prepare are one class's", hooks.prepare.definingClass, hooks.play.definingClass)
                assertEquals("${bundle.name}: pause is theirs too", hooks.prepare.definingClass, hooks.pause.definingClass)

                context.holdStartsWithoutATap()

                fun after(method: Method) = context.method(method.definingClass, method.name, method.parameterTypes.map(Any::toString)).code()
                val internal = after(hooks.playInternal)
                assertEquals("${bundle.name}: playInternal", hooks.grootField.toString(), internal[0].referenceText())
                assertGateFirst("${bundle.name}: playInternal", internal, ALLOW_START, prefix = listOf(Opcode.IGET_OBJECT))
                assertGateFirst("${bundle.name}: play", after(hooks.play), ALLOW_DIRECT_START)
                assertEquals("${bundle.name}: pause", PAUSED, after(hooks.pause)[0].referenceText())
                assertEquals("${bundle.name}: prepare", REBOUND, after(hooks.prepare)[0].referenceText())
                val check = after(hooks.checker)
                val returns = check.indices.filter { check[it].opcode == Opcode.RETURN }
                assertTrue("${bundle.name}: the check returns", returns.isNotEmpty())
                returns.forEach { assertEquals("${bundle.name}: the check's return at $it", AUTOPLAY_ALLOWED, check[it - 2].referenceText()) }
                assertEquals("${bundle.name}: touch", TOUCH, after(hooks.touch)[0].referenceText())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertGateFirst(what: String, code: List<Instruction>, hook: String, prefix: List<Opcode> = emptyList()) {
        val gate = listOf(
            if (prefix.isEmpty()) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC,
            Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.RETURN_VOID,
        )
        assertEquals("$what: the gate's opcodes", prefix + gate, code.take(prefix.size + gate.size).map { it.opcode })
        assertEquals("$what: the hook called", hook, code[prefix.size].referenceText())
        val branch = prefix.size + 2
        assertEquals("$what: a yes lands on the original first instruction", prefix.size + gate.size, code.target(branch))
    }

    private fun assertUntouched(context: BytecodePatchContext) {
        val hooks = setOf(ALLOW_START, ALLOW_DIRECT_START, PAUSED, REBOUND, AUTOPLAY_ALLOWED, TOUCH)
        for ((type, name) in listOf(player to "A0J", groot to "A0X", groot to "A0V", groot to "A0S", checker to "A01")) {
            assertTrue("$type->$name changed", context.method(type, name).code().none { it.referenceText() in hooks })
        }
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>? = null): Method =
        classDefBy(type).methods.first { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** The index [branch] at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    private fun classes(grootFromElsewhere: Boolean = false, secondPause: Boolean = false, activity: Boolean = true): List<ClassDef> {
        val videoPlayer = classDef(
            player,
            listOf(
                // playInternal: static, (player, reason, playAfterSeek, fromPrepare), two locals.
                method(player, "A0J", listOf(player, string, "Z", "Z"), "V", 6, static = true, body = """
                    const-string v0, "$PLAY_INTERNAL"
                    ${if (grootFromElsewhere) "sget-object v1, $player->shared:$groot" else "iget-object v1, p0, $player->groot:$groot"}
                    if-eqz v1, :end
                    invoke-virtual { v1, p1, p2 }, $groot->A0X(Ljava/lang/String;Z)V
                    ${if (grootFromElsewhere) "iget-object v1, v0, $player->groot:$groot" else ""}
                    :end
                    return-void
                """),
            ),
            listOf(
                ImmutableField(player, "groot", groot, AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField(player, "shared", groot, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null),
            ),
        )
        val pause = { name: String ->
            method(groot, name, listOf(string), "V", 3, static = false, body = """
                iget-object v0, p0, $groot->core:$core
                invoke-virtual { v0, p1 }, $core->A0b(Ljava/lang/String;)V
                return-void
            """)
        }
        val grootPlayer = classDef(
            groot,
            listOfNotNull(
                method(groot, "A0S", listOf("Landroid/view/ViewGroup;", "Ljava/lang/Object;", "Ljava/lang/Integer;"), "V", 25, static = false, body = """
                    const-string v0, "$GROOT_PREPARE"
                    return-void
                """),
                method(groot, "A0X", listOf(string, "Z"), "V", 4, static = false, body = """
                    const-string v0, "retry"
                    const-string v0, "play_after_recovery"
                    return-void
                """),
                pause("A0V"),
                if (secondPause) pause("A0U") else null,
                method(groot, "A0W", listOf(string), "V", 3, static = false, body = """
                    const-string v0, "current_watching_module"
                    return-void
                """),
            ),
            listOf(ImmutableField(groot, "core", core, AccessFlags.PUBLIC.value, null, null, null)),
        )
        val autoplayChecker = classDef(
            checker,
            listOf(
                method(checker, "A01", emptyList(), "Z", 3, static = false, body = """
                    const-string v0, "VideoAutoplayChecker"
                    const-string v0, "zero_rating_or_data_saver"
                    const/4 v1, 0x1
                    if-eqz v1, :done
                    const/4 v1, 0x0
                    return v1
                    :done
                    return v1
                """),
            ),
        )
        val fragmentActivity = classDef(
            FRAGMENT_ACTIVITY,
            listOf(
                method(FRAGMENT_ACTIVITY, "dispatchTouchEvent", listOf("Landroid/view/MotionEvent;"), "Z", 3, static = false, body = """
                    const/4 v0, 0x0
                    return v0
                """),
            ),
        )
        return listOfNotNull(videoPlayer, grootPlayer, autoplayChecker, if (activity) fragmentActivity else null)
    }

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

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods)
}
