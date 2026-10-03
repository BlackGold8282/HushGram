/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The native queue location and its existing removal, proved before any patch is written. */
internal class StoryRetryQueue(
    val owner: String,
    val run: String,
    val beforeBuild: Int,
    val loop: Int,
    val batch: Int,
    val key: Int,
    val scratch: Int,
    val retire: MethodReference,
)

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val MAP = "Ljava/util/Map;"
private const val HASH_MAP = "Ljava/util/HashMap;"
private const val LINKED_MAP = "Ljava/util/LinkedHashMap;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val CLAIM_TAG = "null cannot be cast to non-null type T of com.instagram.store.PendingActionStore"

private fun refuseQueue(detail: String): Nothing = throw PatchException("$PATCH: story retry queue $detail")

/**
 * 449's pending action loop takes a snapshot of keys, moves one pending item into the in-flight
 * map, builds its request, and attaches a callback. Its removal only removes that key from the
 * owned in-flight HashMap under its lock. A held story can use that removal and continue the
 * snapshot loop before any callback or request is allocated. No other store gets this null path.
 * These shapes are deliberately narrow: a changed ownership or loop refuses before mutation.
 */
internal fun BytecodePatchContext.findStoryRetryQueue(store: ClassDef, retry: StoreRetry, reader: Method): StoryRetryQueue {
    if (!AccessFlags.FINAL.isSet(store.accessFlags)) refuseQueue("store isn't final")
    val base = store.superclass?.let { classDefByOrNull(it) } ?: refuseQueue("has no native owner")
    if (!AccessFlags.PUBLIC.isSet(base.accessFlags) || !AccessFlags.ABSTRACT.isSet(base.accessFlags) || base.interfaces.isNotEmpty()) {
        refuseQueue("owner isn't a public abstract class without interfaces")
    }
    val bridge = store.methods.single { it.name == retry.name && it.parameterTypes.map(Any::toString) == retry.parameters }
    val suffix = bridge.code().drop(retry.build + 1)
    if (bridge.implementation!!.tryBlocks.isNotEmpty() || suffix.size < 2 || suffix.first().opcode != Opcode.MOVE_RESULT_OBJECT ||
        suffix.last().opcode != Opcode.RETURN_OBJECT || suffix.first().namedRegisters() != suffix.last().namedRegisters() ||
        suffix.drop(1).dropLast(1).any { it.opcode != Opcode.NOP }) refuseQueue("bridge has cleanup or other work after its build")
    val build = base.methods.singleOrNull {
        it.name == bridge.name && it.parameterTypes.map(Any::toString) == retry.parameters && it.returnType == bridge.returnType &&
            AccessFlags.ABSTRACT.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuseQueue("bridge doesn't override its owner's request builder")
    if (retry.parameters != listOf(OBJECT) || !bridge.returnType.startsWith("L")) refuseQueue("bridge has an unsupported type")

    val callers = mutableListOf<Pair<Method, Int>>()
    classDefForEach { type ->
        if (type.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        for (method in type.methods) for ((at, instruction) in method.code().withIndex()) {
            if (instruction.call()?.same(build) == true) callers += method to at
        }
    }
    val (run, at) = callers.singleOrNull() ?: refuseQueue("expected one request-builder call, found ${callers.size}")
    if (run.definingClass != base.type || run.parameterTypes.isNotEmpty() || run.returnType != "V" ||
        AccessFlags.STATIC.isSet(run.accessFlags) || !AccessFlags.DECLARED_SYNCHRONIZED.isSet(run.accessFlags)) {
        refuseQueue("request builder isn't called by its owner's synchronized loop")
    }
    val code = Shape(run,
        Opcode.MOVE_OBJECT, Opcode.MONITOR_ENTER, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT,
        Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_4,
        Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.GOTO, Opcode.MONITOR_EXIT,
        Opcode.RETURN_VOID, Opcode.MOVE_EXCEPTION, Opcode.MONITOR_EXIT, Opcode.THROW,
    )
    if (at != 20) refuseQueue("request builder moved within the loop")
    val self = run.localRegisterCount()
    val lock = code.reg(0)
    val iterator = code.reg(6)
    val key = code.reg(12)
    val item = code.reg(15)
    val request = code.reg(21)
    code.registers(0, lock, self)
    code.registers(1, lock)
    code.registers(3, self)
    code.registers(5, code.reg(4))
    code.javaCall(5, "iterator", emptyList(), "Ljava/util/Iterator;")
    code.registers(8, iterator)
    code.javaCall(8, "hasNext", emptyList(), "Z")
    code.registers(10, code.reg(9))
    code.branch(10, 34)
    code.registers(11, iterator)
    code.javaCall(11, "next", emptyList(), OBJECT)
    code.registers(13, key)
    if (code.reference(13)?.toString() != STRING) refuseQueue("key isn't a String")
    code.registers(14, self, key)
    code.registers(16, item)
    code.branch(16, 8)
    code.registers(17, self, key)
    code.registers(19, code.reg(18))
    code.branch(19, 8)
    code.registers(20, self, item)
    code.registers(27, request, code.reg(25))
    code.registers(32, code.reg(31), request)
    code.branch(33, 8)
    code.registers(34, lock)
    code.registers(37, lock)
    code.registers(38, code.reg(36))
    code.monitor(1, listOf(34, 37), 36, listOf(20, 21, 22))
    run.requireThisIntact(PATCH, listOf(0, 3, 14, 17, 20, 22, 28))
    if (20 in run.jumpTargets()) refuseQueue("a branch skips the cancellation site")
    if (self > 15 || key > 15 || item > 15) refuseQueue("operands exceed the cancellation instruction range")

    val claim = base.method(code.call(17))
    val move = Shape(claim,
        Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.IGET_OBJECT, Opcode.MONITOR_ENTER, Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.MONITOR_EXIT, Opcode.RETURN,
        Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_STRING, Opcode.INVOKE_STATIC,
        Opcode.INVOKE_INTERFACE, Opcode.MONITOR_EXIT, Opcode.CONST_4, Opcode.RETURN, Opcode.MOVE_EXCEPTION,
        Opcode.MONITOR_EXIT, Opcode.THROW,
    )
    claim.nativeMethod(listOf(STRING), "Z")
    val pending = move.field(4, LINKED_MAP, base.type)
    val inFlight = move.field(10, MAP, base.type)
    val monitor = move.field(2, OBJECT, base.type)
    val claimSelf = claim.localRegisterCount()
    val claimKey = claim.parameterRegisterNumber(0)
    move.registers(2, move.reg(2), claimSelf)
    move.registers(3, move.reg(2))
    move.registers(4, move.reg(4), claimSelf)
    move.registers(5, move.reg(4), claimKey)
    move.javaCall(5, "containsKey", listOf(OBJECT), "Z")
    move.branch(7, 10)
    move.registers(10, move.reg(10), claimSelf)
    move.registers(11, move.reg(4), claimKey)
    move.javaCall(11, "remove", listOf(OBJECT), OBJECT)
    move.registers(15, move.reg(10), claimKey, move.reg(12))
    move.javaCall(15, "put", listOf(OBJECT, OBJECT), OBJECT)
    move.monitor(3, listOf(8, 16, 20), 19, listOf(4, 5, 10, 11, 14, 15))
    if ((move.reference(13) as? StringReference)?.string != CLAIM_TAG) refuseQueue("claim lacks its native store marker")
    claim.requireParameterIntact(PATCH, 0, listOf(1, 5, 11, 15))
    claim.requireThisIntact(PATCH, listOf(2, 4, 10))
    base.requireOwnedMaps(pending, inFlight, monitor)

    base.requireSnapshot(code.call(3), pending, monitor)
    base.requireLookup(code.call(14), pending, inFlight, monitor)
    val retire = base.methods.singleOrNull { candidate -> candidate.removesOnly(inFlight, monitor) }
        ?: refuseQueue("has no unique allocation-free native in-flight removal")
    requireDiskCleanup(reader, run)
    val scratch = run.freeLocalsAt(PATCH, 20, 1, targets = listOf(8)).single()
    return StoryRetryQueue(base.type, run.name, 20, 8, item, key, scratch, retire)
}

/** Selection happens before the native builder, so a stock null request is never a cancellation signal. */
internal fun BytecodePatchContext.hookStoryRetryQueue(found: StorySeenTargets) {
    val queue = found.queue ?: return
    val run = mutableClassDefBy(queue.owner).methods.single { it.name == queue.run && it.parameterTypes.isEmpty() && it.returnType == "V" }
    val native = run.implementation!!.instructions[queue.beforeBuild]
    val next = run.implementation!!.instructions[queue.loop]
    run.addInstructionsWithLabels(queue.beforeBuild, """
        instance-of v${queue.scratch}, p0, ${found.store}
        if-eqz v${queue.scratch}, :native
        invoke-static { p0, v${queue.batch} }, $TO_RETRY
        move-result-object v${queue.batch}
        if-nez v${queue.batch}, :native
        invoke-static { p0, v${queue.key} }, $RETIRE_RETRY
        goto :next
    """, ExternalLabel("native", native), ExternalLabel("next", next))
}

private fun ClassDef.requireOwnedMaps(pending: FieldReference, inFlight: FieldReference, monitor: FieldReference) {
    for (field in listOf(pending, inFlight, monitor)) {
        val declared = fields.singleOrNull { it.toString() == field.toString() } ?: refuseQueue("ownership field isn't declared")
        if (!AccessFlags.FINAL.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) refuseQueue("ownership field isn't final")
    }
    val constructor = methods.filter { it.name == "<init>" }.singleOrNull { it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) }
        ?: refuseQueue("has no unique account constructor")
    if (methods.count { it.name == "<init>" } != 1) refuseQueue("has another ownership constructor")
    val code = Shape(constructor, Opcode.INVOKE_DIRECT, Opcode.IPUT_OBJECT, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT,
        Opcode.IPUT_OBJECT, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT, Opcode.IPUT_OBJECT, Opcode.NEW_INSTANCE,
        Opcode.INVOKE_DIRECT, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID)
    for ((fresh, field, kind) in listOf(
        Triple(2, pending, LINKED_MAP), Triple(5, inFlight, HASH_MAP), Triple(8, monitor, OBJECT),
    )) {
        val start = fresh + 1
        val put = fresh + 2
        val register = code.reg(fresh)
        if ((code.reference(fresh) as? TypeReference)?.type != kind || code.call(start).toString() != "$kind-><init>()V" ||
            code.reference(put)?.toString() != field.toString()) refuseQueue("doesn't create its own native map and lock")
        code.registers(start, register)
        code.registers(put, register, constructor.localRegisterCount())
    }
    constructor.requireThisIntact(PATCH, listOf(0, 1, 4, 7, 10))
}

private fun ClassDef.requireSnapshot(reference: MethodReference, pending: FieldReference, monitor: FieldReference) {
    val method = method(reference)
    method.nativeMethod(emptyList(), ARRAY_LIST)
    val code = Shape(method, Opcode.IGET_OBJECT, Opcode.MONITOR_ENTER, Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT, Opcode.MONITOR_EXIT, Opcode.RETURN_OBJECT,
        Opcode.MOVE_EXCEPTION, Opcode.MONITOR_EXIT, Opcode.THROW)
    if (code.reference(0).toString() != monitor.toString() || code.reference(2).toString() != pending.toString() ||
        code.reference(5).toString() != ARRAY_LIST || code.call(6).toString() != "$ARRAY_LIST-><init>(Ljava/util/Collection;)V") {
        refuseQueue("iteration isn't over an owned key snapshot")
    }
    code.registers(0, code.reg(0), method.localRegisterCount())
    code.registers(2, code.reg(2), method.localRegisterCount())
    code.registers(3, code.reg(2))
    code.javaCall(3, "keySet", emptyList(), "Ljava/util/Set;")
    code.registers(6, code.reg(5), code.reg(4))
    code.registers(8, code.reg(5))
    code.monitor(1, listOf(7, 10), 9, listOf(2, 3, 6))
    method.requireThisIntact(PATCH, listOf(0, 2))
}

private fun ClassDef.requireLookup(reference: MethodReference, pending: FieldReference, inFlight: FieldReference, monitor: FieldReference) {
    val method = method(reference)
    method.nativeMethod(listOf(STRING), OBJECT)
    val code = Shape(method, Opcode.IGET_OBJECT, Opcode.MONITOR_ENTER, Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT,
        Opcode.MONITOR_EXIT, Opcode.RETURN_OBJECT, Opcode.MOVE_EXCEPTION, Opcode.MONITOR_EXIT, Opcode.THROW)
    if (code.reference(0).toString() != monitor.toString() || code.reference(2).toString() != pending.toString() ||
        code.reference(6).toString() != inFlight.toString()) refuseQueue("lookup uses another store's maps")
    code.registers(2, code.reg(2), method.localRegisterCount())
    code.registers(3, code.reg(2), method.parameterRegisterNumber(0))
    code.javaCall(3, "containsKey", listOf(OBJECT), "Z")
    code.branch(5, 7)
    code.registers(6, code.reg(2), method.localRegisterCount())
    code.registers(7, code.reg(2), method.parameterRegisterNumber(0))
    code.javaCall(7, "get", listOf(OBJECT), OBJECT)
    code.registers(10, code.reg(8))
    code.monitor(1, listOf(9, 12), 11, listOf(2, 3, 6, 7))
    method.requireParameterIntact(PATCH, 0, listOf(3, 7))
    method.requireThisIntact(PATCH, listOf(0, 2, 6))
}

private fun Method.removesOnly(inFlight: FieldReference, monitor: FieldReference): Boolean {
    if (!AccessFlags.PUBLIC.isSet(accessFlags) || !AccessFlags.FINAL.isSet(accessFlags) || AccessFlags.STATIC.isSet(accessFlags) ||
        parameterTypes.map(Any::toString) != listOf(STRING) || returnType != "V") return false
    val code = code()
    if (code.map { it.opcode } != listOf(Opcode.IGET_OBJECT, Opcode.MONITOR_ENTER, Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE,
        Opcode.MONITOR_EXIT, Opcode.RETURN_VOID, Opcode.MOVE_EXCEPTION, Opcode.MONITOR_EXIT, Opcode.THROW)) return false
    val self = localRegisterCount()
    val key = parameterRegisterNumber(0)
    val lock = code[0].namedRegisters().first()
    val map = code[2].namedRegisters().first()
    val matches = code[0].reference()?.toString() == monitor.toString() && code[0].namedRegisters() == listOf(lock, self) &&
        code[1].namedRegisters() == listOf(lock) && code[2].reference()?.toString() == inFlight.toString() &&
        code[2].namedRegisters() == listOf(map, self) && code[3].call()?.toString() == "$MAP->remove($OBJECT)$OBJECT" &&
        code[3].namedRegisters() == listOf(map, key) && code[4].namedRegisters() == listOf(lock) &&
        code[7].namedRegisters() == listOf(lock) && code[8].namedRegisters() == code[6].namedRegisters()
    if (matches) Shape(this, *code.map { it.opcode }.toTypedArray()).monitor(1, listOf(4, 7), 6, listOf(2, 3))
    return matches
}

private fun requireDiskCleanup(reader: Method, run: Method) {
    val code = reader.code()
    val at = code.indices.singleOrNull { code[it].call()?.same(run) == true } ?: refuseQueue("disk reader doesn't run the queue once")
    if (code.size <= at + 4 || code[at + 1].opcode != Opcode.IGET_OBJECT ||
        (code[at + 1].reference() as? FieldReference)?.let { it.definingClass == USER_SESSION && it.name == "userId" && it.type == STRING } != true ||
        code[at + 2].opcode != Opcode.INVOKE_STATIC || code[at + 2].call()?.returnType != STRING ||
        code[at + 3].opcode != Opcode.MOVE_RESULT_OBJECT || code[at + 4].opcode != Opcode.INVOKE_VIRTUAL ||
        code[at + 4].call()?.parameterTypes?.map(Any::toString) != listOf(STRING) || code[at + 4].call()?.returnType != "V" ||
        code[at + 4].namedRegisters().lastOrNull() != code[at + 3].namedRegisters().singleOrNull() ||
        reader.jumpTargets().any { it in at + 2..at + 4 }) refuseQueue("disk reader no longer reaches its native cleanup after the loop")
}

private class Shape(val method: Method, vararg expected: Opcode) {
    private val code = method.code()
    init { if (code.map { it.opcode } != expected.toList()) refuseQueue("${method.name} changed its native shape") }
    fun reg(at: Int): Int = code[at].namedRegisters().first()
    fun registers(at: Int, vararg expected: Int) {
        if (code[at].namedRegisters() != expected.toList()) refuseQueue("${method.name} changes ownership registers at $at")
    }
    fun reference(at: Int) = code[at].reference()
    fun call(at: Int): MethodReference = code[at].call() ?: refuseQueue("${method.name} lacks its native call at $at")
    fun javaCall(at: Int, name: String, parameters: List<String>, result: String) {
        val call = call(at)
        if (!call.definingClass.startsWith("Ljava/util/") || call.name != name || call.parameterTypes.map(Any::toString) != parameters ||
            call.returnType != result) refuseQueue("${method.name} changes its collection operation at $at")
    }
    fun field(at: Int, type: String, owner: String): FieldReference = (reference(at) as? FieldReference)
        ?.takeIf { it.type == type && it.definingClass == owner } ?: refuseQueue("${method.name} reads a foreign ownership field at $at")
    fun branch(at: Int, target: Int) {
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        if (addresses[at] + (code[at] as OffsetInstruction).codeOffset != addresses[target]) refuseQueue("${method.name} changes its loop at $at")
    }
    fun monitor(enter: Int, exits: List<Int>, handler: Int, protected: List<Int>) {
        val lock = code[enter].namedRegisters()
        if (exits.any { code[it].namedRegisters() != lock } || code[handler].opcode != Opcode.MOVE_EXCEPTION ||
            code[handler + 1].opcode != Opcode.MONITOR_EXIT || code[handler + 2].opcode != Opcode.THROW ||
            code[handler + 2].namedRegisters() != code[handler].namedRegisters()) refuseQueue("${method.name} changes its lock cleanup")
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val blocks = method.implementation!!.tryBlocks
        if (blocks.isEmpty() || blocks.any { block -> block.exceptionHandlers.size != 1 ||
                block.exceptionHandlers.single().exceptionType != null || block.exceptionHandlers.single().handlerCodeAddress != addresses[handler] } ||
            protected.any { index -> blocks.none { addresses[index] >= it.startCodeAddress && addresses[index] < it.startCodeAddress + it.codeUnitCount } }) {
            refuseQueue("${method.name} lacks its native catch-all lock cleanup")
        }
    }
}

private fun Method.nativeMethod(parameters: List<String>, result: String) {
    if (!AccessFlags.PUBLIC.isSet(accessFlags) || !AccessFlags.FINAL.isSet(accessFlags) || AccessFlags.STATIC.isSet(accessFlags) ||
        parameterTypes.map(Any::toString) != parameters || returnType != result) refuseQueue("$name isn't its native instance method")
}
private fun ClassDef.method(reference: MethodReference): Method = methods.singleOrNull { it.same(reference) }
    ?: refuseQueue("can't resolve ${reference.name} in the owner")
private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Instruction.call() = reference() as? MethodReference
private fun MethodReference.same(other: MethodReference) = definingClass == other.definingClass && name == other.name &&
    returnType == other.returnType && parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)
