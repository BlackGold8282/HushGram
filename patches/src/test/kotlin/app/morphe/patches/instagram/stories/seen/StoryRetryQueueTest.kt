/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryRetryQueueTest {
    private val fixtures = StorySeenHookTest()

    @Test fun cancellationRetiresOnlyTheStoryStoreAndContinuesItsSnapshotLoop() = verify(fixtures.standIns())

    @Test fun aStockNullRequestStaysOnTheOriginalNativePath() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        verify(classes)
        val context = PatchContexts.of(classes)
        context.holdBackStoryViews()
        // Fault injection after discovery models a native request becoming null at run time.
        val bridge = context.mutableClassDefBy(found.store).methods.single { it.name == found.retry!!.name }
        val last = bridge.code().lastIndex
        val register = bridge.code().last().namedRegisters().single()
        bridge.replaceInstruction(last, "const/16 v$register, 0x0")
        bridge.addInstructionsWithLabels(last + 1, "return-object v$register")
        assertEquals(Opcode.CONST_16, bridge.code()[last].opcode)
        assertEquals(Opcode.RETURN_OBJECT, bridge.code().last().opcode)
        val queue = found.queue!!
        val loop = context.mutableClassDefBy(queue.owner).methods.single { it.name == queue.run }.code()
        val native = queue.beforeBuild + 7
        assertEquals("the stock builder receives the selected batch", queue.batch, loop[native].namedRegisters()[1])
        assertEquals(Opcode.MOVE_RESULT_OBJECT, loop[native + 1].opcode)
        assertEquals("stock null results go through the original callback and scheduler", Opcode.INVOKE_VIRTUAL, loop[native + 2].opcode)
        assertTrue("no cancellation decision reads the native request result", loop.drop(native).none { it.reference() == TO_RETRY || it.reference() == RETIRE_RETRY })
    }

    @Test fun anotherPendingStoreCanReturnNullWithoutStorySelectionOrRetirement() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val bridge = classes.single { it.type == found.store }.methods.single { it.name == found.retry!!.name }
        val other = "Lfixture/OtherPendingStore;"
        val nullBuilder = storyQueueMethod(other, bridge.name, listOf("Ljava/lang/Object;"), bridge.returnType, 3, """
            const/4 v0, 0x0
            return-object v0
        """)
        val unrelated = ImmutableClassDef(other, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            found.queue!!.owner, null, null, null, null, listOf(nullBuilder))
        verify(classes + unrelated)
        val context = PatchContexts.of(classes + unrelated)
        context.holdBackStoryViews()
        val queue = found.queue
        val loop = context.mutableClassDefBy(queue.owner).methods.single { it.name == queue.run }.code()
        assertEquals("only the story store is tested", found.store, loop[queue.beforeBuild].reference())
        assertEquals("another store goes directly to its original native builder", queue.beforeBuild + 7,
            target(loop, queue.beforeBuild + 1))
        val kept = context.classDefByOrNull(other)!!.methods.single()
        assertEquals(nullBuilder.code().map { it.opcode to it.reference() }, kept.code().map { it.opcode to it.reference() })
    }

    @Test fun declaredNativeBuildPreservesTheQueueDiskCleanupAndOtherStores() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            verify(fixtures.fixtureClasses(bundle))
            checked += version
        }
        assertEquals(versions, checked)
    }

    @Test fun sharedOrMutableInFlightMapRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val fields = owner.fields.map { field -> if (field.type == "Ljava/util/Map;")
            ImmutableField(field.definingClass, field.name, field.type, field.accessFlags and AccessFlags.FINAL.value.inv(), null, null, null)
            else field }
        refused(replace(classes, owner, fields = fields), "ownership field isn't final")
        val constructor = owner.methods.single { it.name == "<init>" }
        refused(changed(classes, constructor, 5, "new-instance v0, Ljava/util/LinkedHashMap;"), "doesn't create its own native map")
    }

    @Test fun liveMapIterationRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val method = classes.single { it.type == found.queue!!.owner }.methods.single { it.name == "A05" }
        refused(changed(classes, method, 5, "new-instance v0, Ljava/util/HashMap;"), "iteration isn't over an owned key snapshot")
    }

    @Test fun wrongClaimValueRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val method = classes.single { it.type == found.queue!!.owner }.methods.single { it.name == "A0G" }
        refused(changed(classes, method, 15, "invoke-interface { v2, p1, v0 }, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), "ownership registers")
    }

    @Test fun foreignLookupRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = found.queue!!.owner
        val method = classes.single { it.type == owner }.methods.single { it.name == "A04" }
        refused(changed(classes, method, 6, "iget-object v1, p0, $owner->pending:Ljava/util/LinkedHashMap;"), "lookup uses another store's maps")
    }

    @Test fun wrongRetirementKeyRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val method = classes.single { it.type == found.queue!!.owner }.methods.single { it.name == "A0C" }
        refused(changed(classes, method, 3, "invoke-interface { v0, v1 }, Ljava/util/Map;->remove(Ljava/lang/Object;)Ljava/lang/Object;"), "no unique allocation-free native")
    }

    @Test fun ambiguousRetirementRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val method = owner.methods.single { it.name == "A0C" }
        val second = ImmutableMethod(method.definingClass, "secondRemoval", method.parameters, method.returnType, method.accessFlags,
            null, null, method.implementation)
        refused(replace(classes, owner, methods = owner.methods.toList() + second), "no unique allocation-free native")
    }

    @Test fun missingMonitorHandlerRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val method = owner.methods.single { it.name == found.queue!!.run }
        val bad = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags, null, null,
            ImmutableMethodImplementation(method.implementation!!.registerCount, method.code(), emptyList(), null))
        refused(replace(classes, owner, methods = owner.methods.map { if (it == method) bad else it }), "catch-all lock cleanup")
    }

    @Test fun anotherRequestBuilderCallerRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val run = owner.methods.single { it.name == found.queue!!.run }
        val second = ImmutableMethod(run.definingClass, "anotherLoop", run.parameters, run.returnType, run.accessFlags, null, null, run.implementation)
        refused(replace(classes, owner, methods = owner.methods.toList() + second), "expected one request-builder call")
    }

    @Test fun missingDiskCleanupRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val reader = classes.single { it.type == found.store }.methods.single { it.name == "A0L" }
        refused(changed(classes, reader, 9, "nop"), "disk reader no longer reaches its native cleanup")
    }

    @Test fun bridgeWithWorkAfterItsBuildRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val retry = found.retry!!
        val method = classes.single { it.type == found.store }.methods.single { it.name == retry.name }
        refused(changed(classes, method, retry.build + 2, "invoke-static { }, Lfixture/Checks;->cleanup()V"), "bridge has cleanup or other work")
    }

    private fun verify(classes: List<ClassDef>) {
        val found = PatchContexts.of(classes).findStorySeen()
        val queue = found.queue!!
        val original = classes.single { it.type == queue.owner }.methods.single { it.name == queue.run }
        val context = PatchContexts.of(classes)
        context.holdBackStoryViews()
        val patched = context.mutableClassDefBy(queue.owner).methods.single { it.name == queue.run }
        val code = patched.code()
        val at = queue.beforeBuild
        assertEquals(listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
            Opcode.IF_NEZ, Opcode.INVOKE_STATIC, Opcode.GOTO), code.subList(at, at + 7).map { it.opcode })
        assertEquals(listOf(queue.scratch, original.localRegisterCount()), code[at].namedRegisters())
        assertEquals("only the final story store can take the cancellation path", found.store, code[at].reference())
        assertEquals(TO_RETRY, code[at + 2].reference())
        assertEquals(listOf(original.localRegisterCount(), queue.batch), code[at + 2].namedRegisters())
        assertEquals(listOf(queue.batch), code[at + 3].namedRegisters())
        assertEquals(listOf(queue.batch), code[at + 4].namedRegisters())
        assertEquals(RETIRE_RETRY, code[at + 5].reference())
        assertEquals(listOf(original.localRegisterCount(), queue.key), code[at + 5].namedRegisters())
        assertEquals(at + 7, target(code, at + 1))
        assertEquals(at + 7, target(code, at + 4))
        assertEquals(queue.loop, target(code, at + 6))
        preserved(original, patched, at, 7)

        val retire = context.mutableClassDefBy(STORY_SEEN).methods.single { it.name == "retireRetry" }.code()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.RETURN_VOID), retire.take(3).map { it.opcode })
        assertEquals(queue.owner, retire[0].reference())
        assertEquals(queue.retire.toString(), retire[1].reference())
        assertTrue("cancellation allocates no batch, callback, lambda or request", (code.subList(at, at + 7) + retire.take(3))
            .none { it.opcode in setOf(Opcode.NEW_INSTANCE, Opcode.NEW_ARRAY, Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE) })
        for (type in classes) for (method in type.methods) {
            if (type.type == queue.owner && method.name == queue.run || type.type == found.store && method.name == found.send ||
                type.type == found.binder && method.name == found.binderName || type.type.startsWith("Lapp/hushgram/extension/")) continue
            val after = context.classDefByOrNull(type.type)!!.methods.single { it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType }
            assertEquals("${type.type}->${method.name} changed", method.code().map { it.opcode to it.reference() }, after.code().map { it.opcode to it.reference() })
        }
    }

    private fun preserved(before: Method, after: Method, at: Int, added: Int) {
        val old = before.code()
        val new = after.code()
        fun mapped(index: Int) = if (index < at) index else index + added
        assertEquals(old.size + added, new.size)
        for (i in old.indices) {
            val changed = mapped(i)
            assertEquals("opcode $i changed", old[i].opcode, new[changed].opcode)
            assertEquals("reference $i changed", old[i].reference(), new[changed].reference())
            assertEquals("registers $i changed", old[i].namedRegisters(), new[changed].namedRegisters())
            if (old[i] is OffsetInstruction) assertEquals("native branch $i changed", mapped(target(old, i)), target(new, changed))
        }
        val oldAddresses = addresses(old)
        val newAddresses = addresses(new)
        fun moved(address: Int) = newAddresses[mapped(oldAddresses.indexOf(address))]
        val blocks = after.implementation!!.tryBlocks.toList()
        assertEquals(before.implementation!!.tryBlocks.size, blocks.size)
        for ((i, block) in before.implementation!!.tryBlocks.withIndex()) {
            assertEquals(moved(block.startCodeAddress), blocks[i].startCodeAddress)
            assertEquals(moved(block.startCodeAddress + block.codeUnitCount), blocks[i].startCodeAddress + blocks[i].codeUnitCount)
            assertEquals(block.exceptionHandlers.map { it.exceptionType to moved(it.handlerCodeAddress) },
                blocks[i].exceptionHandlers.map { it.exceptionType to it.handlerCodeAddress })
        }
    }

    private fun refused(classes: List<ClassDef>, reason: String) {
        val context = PatchContexts.of(classes)
        val error = assertThrows(PatchException::class.java) { context.holdBackStoryViews() }
        assertTrue(error.message, error.message!!.startsWith("$PATCH: story retry queue") && reason in error.message!!)
        for (type in classes) for (method in type.methods) {
            val after = context.classDefByOrNull(type.type)!!.methods.single { it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType }
            assertEquals("${type.type}->${method.name} was mutated before refusal", method.code().map { it.opcode to it.reference() }, after.code().map { it.opcode to it.reference() })
        }
    }

    private fun changed(classes: List<ClassDef>, method: Method, at: Int, body: String): List<ClassDef> {
        val mutable = MutableMethod(ImmutableMethod.of(method))
        mutable.replaceInstruction(at, body)
        val owner = classes.single { it.type == method.definingClass }
        return replace(classes, owner, methods = owner.methods.map { if (it == method) ImmutableMethod.of(mutable) else it })
    }
    private fun replace(classes: List<ClassDef>, owner: ClassDef, fields: Iterable<com.android.tools.smali.dexlib2.iface.Field> = owner.fields,
        methods: Iterable<Method> = owner.methods): List<ClassDef> = classes.map { if (it.type != owner.type) it else
        ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass, owner.interfaces, null, null, fields, methods) }
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
    private fun addresses(code: List<Instruction>) = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    private fun target(code: List<Instruction>, at: Int): Int {
        val addresses = addresses(code)
        return addresses.indexOf(addresses[at] + (code[at] as OffsetInstruction).codeOffset)
    }
}
