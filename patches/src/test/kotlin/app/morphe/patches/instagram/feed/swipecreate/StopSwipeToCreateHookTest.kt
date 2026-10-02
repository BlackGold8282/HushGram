/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.swipecreate

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
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StopSwipeToCreateHookTest {
    private val container = SWIPE_CONTAINER
    private val config = POSITION_CONFIG
    private val spring = "Lfixture/Spring;"
    private val event = "Landroid/view/MotionEvent;"
    private val makeConfig = "$config-><init>(Ljava/lang/Object;JLjava/lang/String;FZ)V"

    /** The hook the patch writes is in the SwipeToCreate the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HOLD.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HOLD is not in the extension: $declared", HOLD.substringAfter("->") in declared)
    }

    /**
     * In front of the animate flag's read, the hook gets the clamped target, where the panels are
     * and the config's reason, the field the drag handler's "swipe" goes in past a long parameter.
     * A 0 goes on to the flag's read, and a 1 sets the target and the flag to 0 and skips it.
     */
    @Test
    fun theMoveIsAskedAfterItsFlagAndEveryJumpStillLands() {
        val context = PatchContexts.of(classes())

        context.stop()

        val setter = context.setter()
        assertHeld("the stand-in", setter, target = 0, flag = 3, reasonField = "$config->reason:Ljava/lang/String;")
        val code = setter.code()
        val hold = code.indexOfFirst { it.referenceText() == HOLD }
        assertEquals("the stand-in: the target, the free local and the flag's register", listOf(0, 2, 3), code[hold].arguments())
        val rest = code.indexOfLast { it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == 3 }
        assertEquals("the flag's branch still goes to the at-rest move", "setAtRest", (code[setter.targetOf(rest)].reference() as MethodReference).name)
        assertEquals("one hook", 1, context.mutableClassDefBy(container).methods.sumOf { method -> method.code().count { it.referenceText() == HOLD } })
    }

    /** A build the patch can't read fails at patch time, saying what it found, and nothing is changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val run = "expected $container->$SET_POSITION to read and clamp its target and then read its animate flag once, found"
        val cases = listOf(
            classes(containerType = "Lfixture/OtherContainer;") to "$container isn't in this build",
            classes(configType = "Lfixture/OtherConfig;") to "$config isn't in this build",
            classes(setterName = "setPosition") to "expected one $SET_POSITION($config)V in $container, found 0",
            classes(clampedName = "getPosition") to "expected one $CLAMPED_POSITION()F in $container, found 0",
            classes(clampedPrivate = false) to "$container->$CLAMPED_POSITION isn't a private instance method",
            classes(scrollName = "onDrag") to "expected one $ON_SCROLL in $container, found 0",
            classes(drags = 0) to "expected $container->$ON_SCROLL to load \"$DRAG\" once, found 0",
            classes(drags = 2) to "expected $container->$ON_SCROLL to load \"$DRAG\" once, found 2",
            classes(makes = 0) to "expected $container->$ON_SCROLL to make one $config, found 0",
            classes(makes = 2) to "expected $container->$ON_SCROLL to make one $config, found 2",
            classes(dragHandedOver = false) to "doesn't hand \"$DRAG\" to the $config it makes",
            classes(stores = 0) to "expected $config's constructor to store the reason in one field, found 0",
            classes(stores = 2) to "expected $config's constructor to store the reason in one field, found 2",
            classes(runs = 0) to "$run 0",
            classes(runs = 2) to "$run 2",
            classes(jumpIntoRun = true) to "jumps into the read of its target and animate flag",
            classes(thisReplaced = true) to "writes over this",
            classes(configReplaced = true) to "writes over parameter 0",
            classes(busyLocals = true) to "needs 1",
            classes(flagIntoConfig = true) to "reads its animate flag into v5, which the hook needs for something else",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.stop() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    assertEquals(
                        "$expected: ${original.type}->${method.name} changed",
                        method.code().map { it.describe() }, now.getValue(method.key()).code().map { it.describe() },
                    )
                }
            }
        }
    }

    /** In each declared build the container's move is asked once, right after its animate flag. */
    @Test
    fun eachDeclaredBuildAsksBeforeTheSpringMoves() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                // Copied, and as the patcher reads an APK, a new object for an instruction on each read.
                for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                    val what = "${bundle.name} ($read)"
                    val classes = classesOf(bundle, setOf(container, config)).values
                    assertEquals("$what: the container and its config", 2, classes.size)
                    val context = PatchContexts.of(classes)

                    context.stop()

                    val written = context.mutableClassDefBy(container).methods.filter { method -> method.code().any { it.referenceText() == HOLD } }
                    assertEquals("$what: methods asking", listOf(SET_POSITION), written.map { it.name })
                    val setter = written.single()
                    val code = setter.code()
                    val hold = code.indexOfFirst { it.referenceText() == HOLD }
                    val flag = (code[hold + 6] as OneRegisterInstruction).registerA
                    val target = (code[hold - 5] as OneRegisterInstruction).registerA
                    val reason = code[hold - 1].reference() as FieldReference
                    assertEquals("$what: the reason's class", config, reason.definingClass)
                    assertHeld(what, setter, target, flag, reason.toString())
                    assertEquals("$what: what comes after the flag's read", Opcode.INVOKE_DIRECT, code[hold + 7].opcode)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The clamped target, then the hook: where the panels are, the reason read from the config into
     * the flag's register, the call with the target, the answer, a skip on 0 to the flag's read, and
     * otherwise the target and the flag set to 0 and a jump past the read.
     */
    private fun assertHeld(what: String, setter: MutableMethod, target: Int, flag: Int, reasonField: String) {
        val code = setter.code()
        val holds = code.indices.filter { code[it].referenceText() == HOLD }
        assertEquals("$what: asks", 1, holds.size)
        val hold = holds.single()
        val self = setter.implementation!!.registerCount - 2
        val configRegister = self + 1
        assertEquals("$what: the clamp", Opcode.INVOKE_DIRECT, code[hold - 6].opcode)
        assertEquals("$what: the clamp's answer", Opcode.MOVE_RESULT, code[hold - 5].opcode)
        assertEquals("$what: the target", target, (code[hold - 5] as OneRegisterInstruction).registerA)
        assertEquals("$what: where the panels are", "$container->$CLAMPED_POSITION()F", code[hold - 4].referenceText())
        assertEquals("$what: read on this", listOf(self), code[hold - 4].arguments())
        assertEquals("$what: its answer", Opcode.MOVE_RESULT, code[hold - 3].opcode)
        val current = (code[hold - 3] as OneRegisterInstruction).registerA
        assertEquals("$what: the config moved", Opcode.MOVE_OBJECT_FROM16, code[hold - 2].opcode)
        assertEquals("$what: the config moved into the flag's register", listOf(flag, configRegister), code[hold - 2].twoRegisters())
        assertEquals("$what: the reason read", Opcode.IGET_OBJECT, code[hold - 1].opcode)
        assertEquals("$what: the reason read", listOf(flag, flag), code[hold - 1].twoRegisters())
        assertEquals("$what: the reason's field", reasonField, code[hold - 1].referenceText())
        assertEquals("$what: the call", listOf(target, current, flag), code[hold].arguments())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[hold + 1].opcode)
        assertEquals("$what: the answer's register", current, (code[hold + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the skip", Opcode.IF_EQZ, code[hold + 2].opcode)
        assertEquals("$what: the skip's register", current, (code[hold + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the skip lands on the flag's read", hold + 6, setter.targetOf(hold + 2))
        for ((offset, register) in listOf(3 to target, 4 to flag)) {
            assertEquals("$what: a 0 into v$register", Opcode.CONST_16, code[hold + offset].opcode)
            assertEquals("$what: a 0 into v$register", register, (code[hold + offset] as OneRegisterInstruction).registerA)
            assertEquals("$what: a 0 into v$register", 0, (code[hold + offset] as NarrowLiteralInstruction).narrowLiteral)
        }
        assertEquals("$what: the jump past the read", Opcode.GOTO, code[hold + 5].opcode)
        assertEquals("$what: the jump past the read", hold + 7, setter.targetOf(hold + 5))
        assertEquals("$what: the flag's read", Opcode.IGET_BOOLEAN, code[hold + 6].opcode)
        assertEquals("$what: the flag's read", listOf(flag, configRegister), code[hold + 6].twoRegisters())
        assertTrue("$what: the borrowed register fits an invoke", current <= 15)
        assertTrue("$what: the borrowed register is neither the target nor the flag", current != target && current != flag)
        assertNotEquals("$what: the flag isn't the target", target, flag)
        assertFalse("$what: a jump lands inside the hook", (hold - 3..hold + 5).any { it in setter.jumpTargets() })
    }

    private fun BytecodePatchContext.stop() = stopSwipeToCreate(findSwipeToCreate())

    private fun BytecodePatchContext.setter(): MutableMethod =
        mutableClassDefBy(container).methods.single { it.name == SET_POSITION }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The container, whose move copies the config's reason, reads and clamps its target, reads its
     * animate flag and then moves the spring, animated or at rest; its private read of where the
     * panels are; and its drag handler, which makes a config with "swipe" as its reason. The
     * config's constructor stores that parameter, which comes after a long, in its reason field.
     */
    private fun classes(
        containerType: String = container,
        configType: String = config,
        setterName: String = SET_POSITION,
        clampedName: String = CLAMPED_POSITION,
        clampedPrivate: Boolean = true,
        scrollName: String = ON_SCROLL,
        drags: Int = 1,
        makes: Int = 1,
        dragHandedOver: Boolean = true,
        stores: Int = 1,
        runs: Int = 1,
        jumpIntoRun: Boolean = false,
        thisReplaced: Boolean = false,
        configReplaced: Boolean = false,
        busyLocals: Boolean = false,
        flagIntoConfig: Boolean = false,
    ): List<ClassDef> {
        val run = """
            iget v0, p1, $config->target:F
            invoke-direct { p0, v0 }, $container->clamp(F)F
            move-result v0
            iget-boolean ${if (flagIntoConfig) "p1" else "v3"}, p1, $config->animate:Z
        """
        val setter = method(containerType, setterName, listOf(config), "V", 4, private = true, body = """
            ${if (jumpIntoRun) "if-eqz p1, :flag" else ""}
            iget-object v0, p1, $config->reason:Ljava/lang/String;
            iput-object v0, p0, $container->lastReason:Ljava/lang/String;
            ${if (thisReplaced) "move-object p0, p1" else ""}
            ${if (configReplaced) "const/4 p1, 0x0" else ""}
            ${if (runs == 0) "iget v0, p1, $config->target:F\nmove v3, v0" else run.replace("iget-boolean", if (jumpIntoRun) ":flag\niget-boolean" else "iget-boolean")}
            ${if (runs > 1) run else ""}
            ${if (busyLocals) "invoke-static { v1, v2 }, $spring->use(II)V" else ""}
            invoke-direct { p0 }, $container->getSpring()$spring
            move-result-object v2
            float-to-double v0, v0
            if-eqz v3, :rest
            invoke-virtual { v2, v0, v1 }, $spring->animateTo(D)V
            :done
            return-void
            :rest
            invoke-virtual { v2, v0, v1 }, $spring->setAtRest(D)V
            goto :done
        """)
        val clamped = method(containerType, clampedName, emptyList(), "F", 1, private = clampedPrivate, body = """
            const/4 v0, 0x0
            return v0
        """)
        val clamp = method(containerType, "clamp", listOf("F"), "F", 0, private = true, body = "return p1")
        val getSpring = method(containerType, "getSpring", emptyList(), spring, 1, private = true, body = """
            const/4 v0, 0x0
            return-object v0
        """)
        val make = """
            new-instance v0, $config
            const/4 v1, 0x0
            const-wide/16 v2, 0x0
            ${if (dragHandedOver) "" else "const/4 v4, 0x0"}
            const/4 v5, 0x0
            const/4 v6, 0x0
            invoke-direct/range { v0 .. v6 }, $makeConfig
        """
        val scroll = method(containerType, scrollName, listOf(event, event, "F", "F"), "Z", 8, body = """
            ${(0 until drags).joinToString("\n") { "const-string v4, \"$DRAG\"" }}
            ${(0 until makes).joinToString("\n") { make }}
            invoke-direct { p0, v0 }, $container->$setterName($config)V
            const/4 v0, 0x1
            return v0
        """)
        val containerClass = classDef(
            containerType, listOf(setter, clamped, clamp, getSpring, scroll),
            fields = listOf("lastReason" to "Ljava/lang/String;"),
        )
        val construct = method(configType, "<init>", listOf("Ljava/lang/Object;", "J", "Ljava/lang/String;", "F", "Z"), "V", 0, body = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            iput p5, p0, $config->target:F
            iput-boolean p6, p0, $config->animate:Z
            ${(0 until stores).joinToString("\n") { if (it == 0) "iput-object p4, p0, $config->reason:Ljava/lang/String;" else "iput-object p4, p0, $config->other:Ljava/lang/String;" }}
            return-void
        """)
        val configClass = classDef(
            configType, listOf(construct),
            fields = listOf("target" to "F", "animate" to "Z", "reason" to "Ljava/lang/String;", "other" to "Ljava/lang/String;"),
        )
        return listOf(containerClass, configClass)
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        private: Boolean = false,
        body: String,
    ): Method {
        var flags = if (private) AccessFlags.PRIVATE.value else AccessFlags.PUBLIC.value
        if (name == "<init>") flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + 1 + parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<Pair<String, String>> = emptyList()): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value, null, null, null) },
            methods,
        )

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")")

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.twoRegisters(): List<Int> = (this as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) }

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    /** An instruction as text: its opcode, registers, literal and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        if (this@describe is NarrowLiteralInstruction) append(" #$narrowLiteral")
        referenceText()?.let { append(" $it") }
    }
}
