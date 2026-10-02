/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.autoscroll

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.instagram.FixtureDex
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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepReelsAutoScrollHookTest {
    private val plugin = "Lfixture/AutoscrollPlugin;"
    private val preference = "Lfixture/AutoscrollPreference;"
    private val viewer = "Lfixture/ViewerFragment;"
    private val tabAction = "Lfixture/TabAction;"
    private val prefs = "Lfixture/Prefs;"
    private val trace = "Lfixture/Trace;->begin(Ljava/lang/String;)V"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val clickParameters = listOf(
        "Landroidx/fragment/app/FragmentActivity;", session, "Lfixture/Logger;", "Lkotlin/jvm/functions/Function0;", "I", "J", "Z",
    )
    private val hooks = setOf(AUTO_SCROLL_ANSWER, AUTO_SCROLL_CHOSEN)

    /** The hooks the patch writes are in the ReelAutoScroll the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in hooks) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * Every return of the check and of the getter is answered, a branch straight to a return
     * included; the handler hands its choice over first thing, from a parameter above v15; the
     * long-press action hands its choice over right after it saves it; and the status is on.
     */
    @Test
    fun everyAnswerAndEveryChoiceGoesThroughTheExtension() {
        val context = PatchContexts.of(classes() + ExtensionDex.classDef(SETTINGS_STATUS))

        keepReelsAutoScrollPatch.execute(context)

        val check = context.method(plugin, "check")
        assertEveryReturnAnswered("the check", check, returns = 3)
        val code = check.code()
        val off = code.indices.filter { code[it].opcode == Opcode.RETURN }[1]
        assertEquals("the branch to the second return lands on its answer", off - 2, check.targetOf(4))
        assertEveryReturnAnswered("the getter", context.method(preference, "enabled"), returns = 1)

        val click = context.method(plugin, "handle")
        assertEquals("the stand-in handler's choice is in v28", 28, click.implementation!!.registerCount - 1)
        assertChoiceFirst("the stand-in", click)
        assertChoiceAfterSave("the stand-in", context.method(tabAction, "run"), "$preference->setEnabled($prefs" + "Z)V")
        assertEquals("nothing else calls chosen", 0, context.method(viewer, "onPause").code().count { it.referenceText() == AUTO_SCROLL_CHOSEN })

        val status = context.method(SETTINGS_STATUS, "reelAutoScroll").code()
        assertEquals("SettingsStatus.reelAutoScroll() isn't switched on", 1, (status.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val oneMarked = "expected one method marked"
        val callers = "expected $preference->setEnabled($prefs" + "Z)V to be called by"
        val cases = listOf(
            classes(checks = 0) to "$oneMarked $IS_AUTOSCROLL_ACTIVE, found 0",
            classes(checks = 2) to "$oneMarked $IS_AUTOSCROLL_ACTIVE, found 2",
            classes(checkMarker = "android_purge_26_q3_${IS_AUTOSCROLL_ACTIVE}V2") to "$oneMarked $IS_AUTOSCROLL_ACTIVE, found 0",
            classes(checkAnswers = "I") to "isn't an instance ($session) method answering a boolean",
            classes(checkStatic = true) to "isn't an instance ($session) method answering a boolean",
            classes(clicks = 0) to "$oneMarked $AUTOSCROLL_MODE_CLICK, found 0",
            classes(clicks = 2) to "$oneMarked $AUTOSCROLL_MODE_CLICK, found 2",
            classes(clickElsewhere = true) to "$AUTOSCROLL_MODE_CLICK isn't in $plugin",
            classes(clickChoice = "I") to "isn't an instance method taking the choice last and returning nothing",
            classes(clickLoops = true) to "jumps back to its first instruction",
            classes(preferences = 0) to "expected one class whose initializer loads \"$AUTOSCROLL_PREFERENCE\", found 0",
            classes(preferences = 2) to "expected one class whose initializer loads \"$AUTOSCROLL_PREFERENCE\", found 2",
            classes(getterNamed = false) to "doesn't name \"$AUTOSCROLL_GETTER_NAME\"",
            classes(getters = 2) to "to have one static getter answering a boolean, found 2",
            classes(setters = 0) to "to have one static setter taking a boolean, found 0",
            classes(checkReads = false) to "doesn't read $preference->enabled($prefs)Z",
            classes(clickSaves = false) to "doesn't save to $preference->setEnabled($prefs" + "Z)V",
            classes(pauses = 0) to "$callers $plugin->handle",
            classes(pauseString = "auto_scroll_v2") to "$callers $plugin->handle",
            classes(otherSavers = 1) to "$callers $plugin->handle",
            classes(toggles = 0) to "$callers $plugin->handle",
            classes(toggleKeepsActivity = false) to "$tabAction, which saves to $preference->setEnabled($prefs" + "Z)V, keeps no $MAIN_ACTIVITY",
            classes(toggleTakes = listOf("I")) to "isn't an instance method taking and returning nothing",
            classes(toggleSaves = 2) to "calls $preference->setEnabled($prefs" + "Z)V 2 times, expected once",
            classes(jumpAfterSave = true) to "has no place right after its call to $preference->setEnabled($prefs" + "Z)V",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes + ExtensionDex.classDef(SETTINGS_STATUS))
            val failure = assertThrows(expected, PatchException::class.java) { keepReelsAutoScrollPatch.execute(context) }
            assertTrue("$expected: ${failure.message}", failure.message!!.startsWith("Keep Reels auto scroll on: ") && failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() in hooks } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
            val status = context.method(SETTINGS_STATUS, "reelAutoScroll").code()
            assertFalse("$expected: the status was switched on", status.first() is NarrowLiteralInstruction &&
                (status.first() as NarrowLiteralInstruction).narrowLiteral == 1 && status[1].opcode == Opcode.RETURN)
        }
    }

    /**
     * In each declared build every return of the check and of the getter is answered, the handler
     * hands its choice over first thing and the Reels tab's long-press action right after it saves.
     */
    @Test
    fun eachDeclaredBuildKeepsAutoScroll() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val found = mutableMapOf<String, ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.stringSection.none { it == AUTOSCROLL_PREFERENCE || it.endsWith("_$IS_AUTOSCROLL_ACTIVE") || it.endsWith("_$AUTOSCROLL_MODE_CLICK") }) {
                        return@forEach
                    }
                    for (classDef in dex.classes) {
                        val wanted = classDef.methods.any { method ->
                            method.markers().any { it == IS_AUTOSCROLL_ACTIVE || it == AUTOSCROLL_MODE_CLICK } ||
                                (method.name == "<clinit>" && method.code().any { it.string() == AUTOSCROLL_PREFERENCE })
                        }
                        if (wanted) found[classDef.type] = ImmutableClassDef.of(classDef)
                    }
                }
                val preferenceClass = found.values.single { type -> type.methods.any { m -> m.name == "<clinit>" && m.code().any { it.string() == AUTOSCROLL_PREFERENCE } } }
                val getter = preferenceClass.methods.single { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.size == 1 }
                val setter = preferenceClass.methods.single {
                    AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf(getter.parameterTypes.single().toString(), "Z")
                }
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { it.definingClass == setter.definingClass && it.name == setter.name }) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.type !in found && classDef.methods.any { m -> m.code().any { it.calls(setter) } }) {
                            found[classDef.type] = ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val context = PatchContexts.of(found.values + ExtensionDex.classDef(SETTINGS_STATUS))
                val sites = context.findReelAutoScroll()
                assertEquals("${bundle.name}: the getter", "${getter.definingClass}->${getter.name}", "${sites.getter.definingClass}->${sites.getter.name}")

                keepReelsAutoScrollPatch.execute(context)

                val plugin = found.values.single { type -> type.methods.any { m -> IS_AUTOSCROLL_ACTIVE in m.markers() } }
                val check = context.mutableClassDefBy(plugin.type).methods.single { IS_AUTOSCROLL_ACTIVE in it.markers() }
                assertEveryReturnAnswered("${bundle.name}: the check", check, returns = null)
                val checkCode = check.code()
                assertTrue("${bundle.name}: no branch in the check lands on a return's answer",
                    checkCode.indices.filter { checkCode[it].opcode == Opcode.RETURN }.any { it - 2 in check.jumpTargets() })
                assertTrue("${bundle.name}: the check still reads the getter", checkCode.any { it.calls(getter) })
                assertEveryReturnAnswered("${bundle.name}: the getter", context.method(getter), returns = 1)
                val click = context.mutableClassDefBy(plugin.type).methods.single { AUTOSCROLL_MODE_CLICK in it.markers() }
                assertChoiceFirst(bundle.name, click)

                val savers = found.values.flatMap { type -> type.methods.filter { m -> m.code().any { it.calls(setter) } }.map { type to it } }
                assertEquals("${bundle.name}: the setter's callers", 3, savers.size)
                val (_, toggle) = savers.single { (type, _) -> type.fields.any { it.type == MAIN_ACTIVITY } }
                assertEquals("${bundle.name}: the long-press action", sites.toggle.toString(), "${toggle.definingClass}->${toggle.name}()")
                assertChoiceAfterSave(bundle.name, context.method(toggle), setter.text())
                val (_, pause) = savers.single { (_, method) -> method.name == "onPause" }
                assertEquals("${bundle.name}: onPause isn't hooked", 0, context.method(pause).code().count { it.referenceText() in hooks })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Each return is the end of the answer call and its result, on the return's register; the
     * answer is called once per return; nothing lands between the answer and its return.
     */
    private fun assertEveryReturnAnswered(what: String, method: MutableMethod, returns: Int?) {
        val code = method.code()
        val at = code.indices.filter { code[it].opcode == Opcode.RETURN }
        assertTrue("$what: no returns", at.isNotEmpty())
        if (returns != null) assertEquals("$what: returns", returns, at.size)
        assertEquals("$what: answer calls", at.size, code.count { it.referenceText() == AUTO_SCROLL_ANSWER })
        val targets = method.jumpTargets()
        for (index in at) {
            val register = (code[index] as OneRegisterInstruction).registerA
            assertEquals("$what: the answer before return $index", AUTO_SCROLL_ANSWER, code[index - 2].referenceText())
            assertEquals("$what: the answer's register at $index", listOf(register), code[index - 2].arguments())
            assertEquals("$what: the result at $index", Opcode.MOVE_RESULT, code[index - 1].opcode)
            assertEquals("$what: the result's register at $index", register, (code[index - 1] as OneRegisterInstruction).registerA)
            assertTrue("$what: a jump skips the answer of return $index", (index - 1..index).none { it in targets })
        }
    }

    /** The handler's first instruction hands its last parameter, the choice, to chosen. */
    private fun assertChoiceFirst(what: String, click: MutableMethod) {
        val code = click.code()
        assertEquals("$what: the handler's first call", AUTO_SCROLL_CHOSEN, code.first().referenceText())
        assertEquals("$what: the handler's choice", listOf(click.implementation!!.registerCount - 1), code.first().arguments())
        assertEquals("$what: one chosen in the handler", 1, code.count { it.referenceText() == AUTO_SCROLL_CHOSEN })
        assertTrue("$what: something jumps to the handler's start", 0 !in click.jumpTargets())
    }

    /** Right after the one call saving the choice, the choice it saved goes to chosen. */
    private fun assertChoiceAfterSave(what: String, toggle: MutableMethod, setter: String) {
        val code = toggle.code()
        val save = code.indices.single { code[it].referenceText() == setter }
        assertEquals("$what: right after the save", AUTO_SCROLL_CHOSEN, code[save + 1].referenceText())
        assertEquals("$what: the saved choice", listOf(code[save].arguments()[1]), code[save + 1].arguments())
        assertEquals("$what: one chosen in the action", 1, code.count { it.referenceText() == AUTO_SCROLL_CHOSEN })
        assertTrue("$what: a jump lands on the hook", save + 1 !in toggle.jumpTargets())
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The plugin with its check, which reads the getter on one path, returns false through a branch
     * straight to a return on another and true on a third, and its handler, which saves the choice
     * it takes last; the preference class, its getter and setter; the Reels viewer's onPause,
     * clearing the preference; and the Reels tab's long-press action, which saves its choice.
     */
    private fun classes(
        checks: Int = 1,
        checkMarker: String = "android_purge_26_q3_$IS_AUTOSCROLL_ACTIVE",
        checkAnswers: String = "Z",
        checkStatic: Boolean = false,
        checkReads: Boolean = true,
        clicks: Int = 1,
        clickElsewhere: Boolean = false,
        clickChoice: String = "Z",
        clickLoops: Boolean = false,
        clickSaves: Boolean = true,
        preferences: Int = 1,
        getterNamed: Boolean = true,
        getters: Int = 1,
        setters: Int = 1,
        pauses: Int = 1,
        pauseString: String = AUTO_SCROLL_SURFACE,
        otherSavers: Int = 0,
        toggles: Int = 1,
        toggleKeepsActivity: Boolean = true,
        toggleTakes: List<String> = emptyList(),
        toggleSaves: Int = 1,
        jumpAfterSave: Boolean = false,
    ): List<ClassDef> {
        val setter = "$preference->setEnabled($prefs" + "Z)V"
        val read = if (checkReads) "invoke-static { v0 }, $preference->enabled($prefs)Z" else "invoke-static { v0 }, Lfixture/Other;->enabled($prefs)Z"
        val checkMethods = (0 until checks).map { copy ->
            method(plugin, if (copy == 0) "check" else "check$copy", listOf(session), checkAnswers, 4, static = checkStatic, body = """
                const/4 v1, 0x0
                if-eqz v1, :memory
                const-string v0, "$checkMarker"
                invoke-static { v0 }, $trace
                if-nez v1, :off
                const/4 v0, 0x0
                $read
                move-result v1
                return v1
                :off
                return v1
                :memory
                const/4 v2, 0x1
                return v2
            """)
        }
        val choiceParameters = clickParameters.dropLast(1) + clickChoice
        val save = if (clickSaves) "invoke-static { v0, v1 }, $setter" else "invoke-static { v0, v1 }, Lfixture/Other;->setEnabled($prefs" + "Z)V"
        val clickMethods = (0 until clicks).map { copy ->
            method(plugin, if (copy == 0) "handle" else "handle$copy", choiceParameters, "V", 20, body = """
                ${if (clickLoops) ":top" else ""}
                const/4 v0, 0x0
                const-string v1, "android_purge_26_q3_$AUTOSCROLL_MODE_CLICK"
                invoke-static { v1 }, $trace
                move/from16 v1, p8
                $save
                ${if (clickLoops) "if-eqz v1, :top" else ""}
                return-void
            """)
        }
        val pluginClass = if (clickElsewhere) {
            listOf(classDef(plugin, checkMethods), classDef("Lfixture/OtherPlugin;", clickMethods.map { it.movedTo("Lfixture/OtherPlugin;") }))
        } else {
            listOf(classDef(plugin, checkMethods + clickMethods))
        }

        val preferenceClasses = (0 until preferences).map { copy ->
            val type = if (copy == 0) preference else "Lfixture/OtherPreference;"
            val methods = mutableListOf(
                method(type, "<clinit>", emptyList(), "V", 1, static = true, body = """
                    const-string v0, "clipsAutoscrollEnabled"
                    ${if (getterNamed) "const-string v0, \"$AUTOSCROLL_GETTER_NAME\"" else ""}
                    const-string v0, "$AUTOSCROLL_PREFERENCE"
                    return-void
                """),
            )
            (0 until getters).forEach { methods += method(type, if (it == 0) "enabled" else "enabled$it", listOf(prefs), "Z", 1, static = true, body = """
                const/4 v0, 0x0
                return v0
            """) }
            (0 until setters).forEach { methods += method(type, if (it == 0) "setEnabled" else "setEnabled$it", listOf(prefs, "Z"), "V", 0, static = true, body = """
                return-void
            """) }
            classDef(type, methods)
        }

        val viewerClasses = (0 until pauses).map {
            classDef(viewer, listOf(method(viewer, "onPause", emptyList(), "V", 3, body = """
                const-string v0, "$pauseString"
                const/4 v1, 0x0
                const/4 v2, 0x0
                invoke-static { v1, v2 }, $setter
                return-void
            """)))
        }
        val otherClasses = (0 until otherSavers).map {
            classDef("Lfixture/OtherSaver;", listOf(method("Lfixture/OtherSaver;", "save", emptyList(), "V", 3, body = """
                const/4 v1, 0x0
                const/4 v2, 0x1
                invoke-static { v1, v2 }, $setter
                return-void
            """)))
        }
        val toggleClasses = (0 until toggles).map {
            val saves = (0 until toggleSaves).joinToString("\n") { "invoke-static { v1, v2 }, $setter" }
            val fields = listOf("on" to "Z") + if (toggleKeepsActivity) listOf("activity" to MAIN_ACTIVITY) else emptyList()
            classDef(tabAction, listOf(method(tabAction, "run", toggleTakes, "V", 3, body = """
                iget-boolean v0, p0, $tabAction->on:Z
                xor-int/lit8 v2, v0, 0x1
                const/4 v1, 0x0
                ${if (jumpAfterSave) "if-eqz v2, :after" else ""}
                $saves
                ${if (jumpAfterSave) ":after" else ""}
                const/4 v0, 0x0
                return-void
            """)), fields = fields)
        }
        return pluginClass + preferenceClasses + viewerClasses + otherClasses + toggleClasses
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        body: String,
    ): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
        if (name == "<clinit>") flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun Method.movedTo(owner: String): Method = ImmutableMethod(
        owner, name, parameters, returnType, accessFlags, annotations, hiddenApiRestrictions, implementation,
    )

    private fun classDef(type: String, methods: List<Method>, fields: List<Pair<String, String>> = emptyList()): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value, null, null, null) },
            methods,
        )

    private fun BytecodePatchContext.method(type: String, name: String): MutableMethod =
        mutableClassDefBy(type).methods.single { it.name == name }

    private fun BytecodePatchContext.method(like: Method): MutableMethod =
        mutableClassDefBy(like.definingClass).methods.single {
            it.name == like.name && it.parameterTypes.map(CharSequence::toString) == like.parameterTypes.map(CharSequence::toString)
        }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string

    private fun Instruction.calls(method: Method): Boolean {
        val called = reference() as? MethodReference ?: return false
        return called.definingClass == method.definingClass && called.name == method.name &&
            called.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}
