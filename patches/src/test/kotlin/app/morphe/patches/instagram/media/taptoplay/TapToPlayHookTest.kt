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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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
    private val objectType = "Ljava/lang/Object;"
    private val navigator = "Lfixture/PauseAndMuteNavigator;"
    private val controller = "Lfixture/ClipsVideoPlayerController;"
    private val lookup = "Lfixture/ClipsPlayers;"
    private val holder = "Lfixture/ClipsViewHolder;"
    private val reelPlayer = "Lfixture/ClipsVideoPlayer;"
    private val state = "Lfixture/PlayerState;"
    private val function0 = "Lkotlin/jvm/functions/Function0;"

    /** What the controller's pause logs, which the fixture check finds it by. */
    private val pauseCurrentPlayerLog = "ClipsVideoPlayerController.pauseCurrentPlayer pauseReason="

    /** Every hook the patch writes is in the extension the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(ALLOW_START, ALLOW_DIRECT_START, PAUSED, REBOUND, AUTOPLAY_ALLOWED, TOUCH, RESUME_ON_TAP)) {
            val type = hook.substringBefore("->")
            val declared = ExtensionDex.classDef(type).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        val stubs = ExtensionDex.classDef(REEL_STATE_READER).methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for ((name, count) in REEL_STUBS) {
            val stub = "$name(${objectType.repeat(count)})$objectType"
            assertTrue("the stub $stub is not in the extension: $stubs", stub in stubs)
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

        assertReelTapHooked(context.method(navigator, "A01").code(), decision = 1, navigatorRegister = 5)
        // Each stub, before its first return, reaches one step toward the state of the reel on screen.
        assertEquals(listOf(navigator, "$navigator->A03:$function0", "$function0->invoke()$objectType"), filled(context, "controllerOf"))
        assertEquals(listOf(controller, "$controller->A0f()$holder"), filled(context, "holderOf"))
        assertEquals(listOf(controller, "$controller->A0R:$lookup"), filled(context, "playersOf"))
        assertEquals(listOf(lookup, holder, "$lookup->A01($holder)$reelPlayer"), filled(context, "playerFor"))
        assertEquals(listOf(reelPlayer, "$reelPlayer->Cyy()$state"), filled(context, "stateOf"))
    }

    /** What the filled stub [name] names before its first return. Its own body stays behind it, unreached. */
    private fun filled(context: BytecodePatchContext, name: String): List<String> =
        context.method(REEL_STATE_READER, name).code().takeWhile { it.opcode != Opcode.RETURN_OBJECT }.mapNotNull { it.referenceText() }

    /** The decision goes past the extension, with the navigator, just before the branch to the pause path. */
    private fun assertReelTapHooked(code: List<Instruction>, decision: Int, navigatorRegister: Int, what: String = "the Reels tap") {
        val pausePath = code.indexOfFirst { it.referenceText() == CLIPS_PAUSE }
        val branch = code.indices.single { code[it].opcode == Opcode.IF_EQZ && code.target(it) == pausePath }
        assertEquals("$what: the hook", RESUME_ON_TAP, code[branch - 2].referenceText())
        assertEquals(
            "$what: the decision and the navigator", listOf(decision, navigatorRegister),
            (code[branch - 2] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) },
        )
        assertEquals(
            "$what: the answer replaces the decision", listOf(Opcode.MOVE_RESULT, decision),
            listOf(code[branch - 1].opcode, (code[branch - 1] as OneRegisterInstruction).registerA),
        )
        assertEquals("$what: the branch tests it", decision, (code[branch] as OneRegisterInstruction).registerA)
    }

    /** Each Reels tap the patch can't hook safely fails at patch time with what it found, before anything is written. */
    @Test
    fun aReelsTapThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            Reel(secondPauseBranch = true) to "one branch to the pause path",
            Reel(decision = "p3") to "in a parameter's register",
            Reel(decision = "v16") to "past v15",
            Reel(objectDecision = true) to "v1 holds something other than a boolean",
            Reel(pauseCall = "A0d") to "never pauses through $controller",
            Reel(privateSupplier = true) to "can't reach $navigator->A03",
            Reel(privateHolder = true) to "can't reach $holder",
            Reel(secondHolderAccessor = true) to "one holder accessor on $controller",
            Reel(playerIsInterface = false) to "$reelPlayer isn't an interface",
            Reel(stateNames = listOf("IDLE", "PLAYING")) to "one state accessor on $reelPlayer",
        )
        for ((reel, expected) in cases) {
            val context = PatchContexts.of(classes(reel = reel))
            val failure = assertThrows(PatchException::class.java) { context.holdStartsWithoutATap() }
            assertTrue("$reel: ${failure.message}", failure.message!!.contains(expected))
            assertUntouched(context)
        }
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
                // The Reels tap's navigator and controller, then the classes the controller's pause
                // names, then the classes their methods return, which reaches the player interface
                // and its state enum.
                val reel = FixtureDex.classesHolding(bundle, CLIPS_PAUSE) + FixtureDex.classesHolding(bundle, pauseCurrentPlayerLog)
                val pauses = reel.flatMap { it.methods }.filter { pauseCurrentPlayerLog in it.strings() }
                val near = FixtureDex.classes(bundle, pauses.flatMap { it.namedTypes() }.toSet()).values.toList()
                val returned = FixtureDex.classes(bundle, near.flatMap { it.methods }.map { it.returnType }.toSet()).values.toList()
                val classes = (
                    FixtureDex.classesHolding(bundle, PLAY_INTERNAL) + FixtureDex.classesHolding(bundle, GROOT_PREPARE) +
                        FixtureDex.classesHolding(bundle, AUTOPLAY_CHECKER.last()) +
                        listOfNotNull(FixtureDex.classes(bundle, setOf(FRAGMENT_ACTIVITY))[FRAGMENT_ACTIVITY]) +
                        reel + near + returned + ExtensionDex.classDef(TAP_TO_PLAY) + ExtensionDex.classDef(REEL_STATE_READER)
                    ).distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val hooks = context.findPlayerHooks()
                val reelTap = context.findReelTap()
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
                // `this` is the first register past the locals: the count less the three declared parameters and itself.
                assertReelTapHooked(after(reelTap.tap), reelTap.decision, reelTap.tap.implementation!!.registerCount - 4, "${bundle.name}: the Reels tap")
                assertEquals("${bundle.name}: the state stub", reelTap.state.toString(), filled(context, "stateOf").last())
                assertEquals("${bundle.name}: the controller stub", "$function0->invoke()$objectType", filled(context, "controllerOf").last())
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
        val hooks = setOf(ALLOW_START, ALLOW_DIRECT_START, PAUSED, REBOUND, AUTOPLAY_ALLOWED, TOUCH, RESUME_ON_TAP)
        for ((type, name) in listOf(player to "A0J", groot to "A0X", groot to "A0V", groot to "A0S", checker to "A01", navigator to "A01")) {
            assertTrue("$type->$name changed", context.method(type, name).code().none { it.referenceText() in hooks })
        }
        for ((name, _) in REEL_STUBS) {
            assertEquals("the stub $name was filled", Opcode.SGET_OBJECT, context.method(REEL_STATE_READER, name).code().first().opcode)
        }
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>? = null): Method =
        classDefBy(type).methods.first { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.strings(): Set<String> =
        code().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()

    /** The classes [this] names: its return, and the fields' and calls' types and owners. */
    private fun Method.namedTypes(): Set<String> = (
        listOf(returnType) + code().mapNotNull { (it as? ReferenceInstruction)?.reference }.flatMap { reference ->
            when (reference) {
                is FieldReference -> listOf(reference.definingClass, reference.type)
                is MethodReference -> listOf(reference.definingClass, reference.returnType)
                else -> emptyList()
            }
        }
        ).filter { it.startsWith("L") }.toSet()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** The index [branch] at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    private fun classes(
        grootFromElsewhere: Boolean = false,
        secondPause: Boolean = false,
        activity: Boolean = true,
        reel: Reel = Reel(),
    ): List<ClassDef> {
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
        return listOfNotNull(videoPlayer, grootPlayer, autoplayChecker, if (activity) fragmentActivity else null) +
            reelClasses(reel) + ExtensionDex.classDef(TAP_TO_PLAY) + ExtensionDex.classDef(REEL_STATE_READER)
    }

    /** How a test's Reels tap stand-ins differ from 449's. */
    private data class Reel(
        val secondPauseBranch: Boolean = false,
        /** The register the tap decides in, v1 in 449. */
        val decision: String = "v1",
        /** The decision's register holds an object before the branch. */
        val objectDecision: Boolean = false,
        /** The controller method the pause path calls, A0c in 449. */
        val pauseCall: String = "A0c",
        val privateSupplier: Boolean = false,
        val privateHolder: Boolean = false,
        val secondHolderAccessor: Boolean = false,
        val playerIsInterface: Boolean = true,
        val stateNames: List<String> = PLAYER_STATES,
    )

    /**
     * PauseAndMuteNavigator's tap, shaped like 449's: nine registers, the decision in v1, `this` in
     * v5, the resume path first and the pause path after the branch. Then the
     * ClipsVideoPlayerController it reaches through a Function0 field, whose pause looks up the
     * player for the holder on screen, the holder, the player interface, and its state enum.
     */
    private fun reelClasses(reel: Reel): List<ClassDef> {
        val d = reel.decision
        val wide = d == "v16"
        // Past v15, `this` is too, so the stand-in reads its field through a copy in v5.
        val self = if (wide) "v5" else "p0"
        val setDecision = { value: Int ->
            when {
                reel.objectDecision -> "iget-object $d, $self, $navigator->A03:$function0"
                wide -> "const/16 $d, 0x$value"
                else -> "const/4 $d, 0x$value"
            }
        }
        val tap = classDef(
            navigator,
            listOf(
                method(navigator, "A01", listOf("Landroid/view/View;", objectType, objectType), "V", if (wide) 24 else 9, static = false, body = """
                    ${if (wide) "move-object/from16 v5, p0" else ""}
                    const-string v0, "android_purge_26_q3_$TOGGLE_PAUSE"
                    ${if (d == "p3") "" else setDecision(0)}
                    if-eqz p3, :decided
                    ${if (d == "p3") "" else setDecision(1)}
                    :decided
                    ${if (reel.secondPauseBranch) "if-eqz p2, :pause" else ""}
                    if-eqz $d, :pause
                    const-wide/16 v0, 0x0
                    return-void
                    :pause
                    const-string v3, "$CLIPS_PAUSE"
                    iget-object v0, $self, $navigator->A03:$function0
                    invoke-interface { v0 }, $function0->invoke()$objectType
                    move-result-object v2
                    check-cast v2, $controller
                    const/4 v0, 0x1
                    invoke-virtual { v2, v3, v0, v0 }, $controller->${reel.pauseCall}(Ljava/lang/String;ZZ)I
                    return-void
                """),
            ),
            listOf(ImmutableField(navigator, "A03", function0, if (reel.privateSupplier) AccessFlags.PRIVATE.value else AccessFlags.PUBLIC.value, null, null, null)),
        )
        val clipsController = classDef(
            controller,
            listOf(
                method(controller, "A0c", listOf(string, "Z", "Z"), "I", 7, static = false, body = """
                    const-string v0, "android_purge_26_q3_$PAUSE_CURRENT_PLAYER"
                    invoke-virtual { p0 }, $controller->A0f()$holder
                    move-result-object v2
                    if-eqz v2, :none
                    iget-object v0, p0, $controller->A0R:$lookup
                    invoke-virtual { v0, v2 }, $lookup->A01($holder)$reelPlayer
                    move-result-object v3
                    if-eqz v3, :none
                    invoke-interface { v3, p1 }, $reelPlayer->GS8(Ljava/lang/String;)I
                    move-result v0
                    return v0
                    :none
                    const/4 v0, 0x0
                    return v0
                """),
                method(controller, "A0f", emptyList(), holder, 2, static = false, body = """
                    const/4 v0, 0x0
                    return-object v0
                """),
            ) + if (reel.secondHolderAccessor) listOf(method(controller, "A0g", emptyList(), holder, 2, static = false, body = """
                    const/4 v0, 0x0
                    return-object v0
                """)) else emptyList(),
            listOf(ImmutableField(controller, "A0R", lookup, AccessFlags.PUBLIC.value, null, null, null)),
        )
        val players = classDef(
            lookup,
            listOf(
                method(lookup, "A01", listOf(holder), reelPlayer, 3, static = false, body = """
                    const/4 v0, 0x0
                    return-object v0
                """),
            ),
        )
        val holderClass = ImmutableClassDef(
            holder, if (reel.privateHolder) 0 else AccessFlags.PUBLIC.value, objectType, null, null, null, emptyList(), emptyList(),
        )
        val playerInterface = ImmutableClassDef(
            reelPlayer, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value or (if (reel.playerIsInterface) AccessFlags.INTERFACE.value else 0),
            objectType, null, null, null,
            emptyList(),
            listOf(abstractMethod(reelPlayer, "GS8", listOf(string), "I"), abstractMethod(reelPlayer, "Cyy", emptyList(), state)),
        )
        val stateEnum = ImmutableClassDef(
            state, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value, "Ljava/lang/Enum;", null, null, null,
            emptyList(),
            listOf(
                method(state, "<clinit>", emptyList(), "V", 1, static = true, body = reel.stateNames.joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-void"),
            ),
        )
        return listOf(tap, clipsController, players, holderClass, playerInterface, stateEnum)
    }

    private fun abstractMethod(owner: String, name: String, parameters: List<String>, returns: String): Method = ImmutableMethod(
        owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
        AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
    )

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
