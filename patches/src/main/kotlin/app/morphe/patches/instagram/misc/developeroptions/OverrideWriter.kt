/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
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
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

/** Instagram's own override editor store logs these from its typed put. */
internal const val DEBUG_STORE = "QuickExperimentDebugStore"
internal const val PUT_FAILURE = "[putOverriddenParameter] MobileConfig failed to find "

/** Instagram's own bug import refuses with this when its manager isn't the native one. */
internal const val RUNTIME_NOT_READY = "MobileConfig xplat runtime is not ready yet"

internal const val MANAGER_IMPL = "Lcom/facebook/mobileconfig/MobileConfigManagerHolderImpl;"
internal const val TABLE_IMPL = "Lcom/facebook/mobileconfig/MobileConfigOverridesTableHolder;"
private const val USER = "Lcom/instagram/common/session/UserSession;"
private const val TABLE_FACTORY = "getOrCreateOverridesTable"

/** The value types Instagram's typed put writes, and the native writer each one reaches. */
internal val OVERRIDE_VALUES = linkedMapOf(
    "Z" to "updateOverrideForBool", "J" to "updateOverrideForInt",
    "D" to "updateOverrideForDouble", "Ljava/lang/String;" to "updateOverrideForString",
)

/**
 * Native entry points the writer never reaches: string imports whose argument is a report or a
 * user to fetch from, whole-table wipes, and the reload nothing in Instagram calls.
 */
internal val FORBIDDEN_WRITES = setOf(
    "importOverridesFromUser", "importOverridesFromBug", "loadOverridesFromBugAndSaveResponse",
    "removeAllOverrides", "clearOverrides", "reload", "deleteManagerDirs", "removeOverridesForQEUniverse",
    "updateOverrideForQE", "clearCurrentUserData",
)

/** The extension's writer stubs: name to (parameters, return type). Each is static and found once. */
internal val WRITER_STUBS = linkedMapOf(
    "getOverrideTableNative" to ("Ljava/lang/Object;" to "Ljava/lang/Object;"),
    "setOverrideBooleanNative" to ("Ljava/lang/Object;JI" to "I"),
    "setOverrideLongNative" to ("Ljava/lang/Object;JJ" to "I"),
    "setOverrideDoubleNative" to ("Ljava/lang/Object;JD" to "I"),
    "setOverrideStringNative" to ("Ljava/lang/Object;JLjava/lang/String;" to "I"),
    "removeOverrideNative" to ("Ljava/lang/Object;J" to "I"),
    "getOverrideTypeNative" to ("J" to "I"),
)

internal data class OverrideWriter(
    val delegate: String, val gate: String, val tableGetter: String, val table: String,
    val updates: Map<String, String>, val remove: String, val decoder: String,
)

/**
 * Resolves the writer Instagram's own override editor uses: the typed put and remove of its debug
 * store, the table its store factory takes from the session [model]'s manager, and the native
 * table class behind it. Every check runs before any stub changes, and nothing here reaches a
 * string import, a wipe or a reload.
 */
internal fun BytecodePatchContext.findOverrideWriter(model: String): OverrideWriter {
    val puts = mutableListOf<Method>()
    classDefForEach { clazz ->
        if (clazz.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        clazz.methods.filterTo(puts) { it.texts().containsAll(listOf(DEBUG_STORE, PUT_FAILURE)) }
    }
    val put = puts.only("typed override put")
    val store = put.definingClass
    val putCode = put.implementation!!.instructions.toList()

    // The four typed puts are interface calls on one table type, each made once.
    val updateCalls = putCode.filter { it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE }
        .mapNotNull { it.reference() }.filter { it.name == "updateOverrideForParam" }
    val table = updateCalls.map { it.definingClass }.distinct().only("override table interface")
    val updates = OVERRIDE_VALUES.keys.associateWith { value ->
        updateCalls.filter { it.parameterTypes.map(Any::toString) == listOf("J", value) && it.returnType == "V" }
            .only("typed put for $value").toString()
    }
    if (updateCalls.size != OVERRIDE_VALUES.size) writerRefuse("typed put has unexpected override calls")
    if (putCode.any { it.reference()?.name in FORBIDDEN_WRITES }) writerRefuse("typed put reaches a bulk or string import")
    val decoder = putCode.filter { it.opcode == Opcode.INVOKE_STATIC }.mapNotNull { it.reference() }
        .filter { it.parameterTypes.map(Any::toString) == listOf("J") && it.returnType == "I" }
        .distinctBy(Any::toString).only("override type decoder")
    publicStatic(decoder)

    // The interface declares what the store calls, plus the remove its reset calls.
    val tableClass = writerClass(table)
    if (!AccessFlags.INTERFACE.isSet(tableClass.accessFlags) || !AccessFlags.PUBLIC.isSet(tableClass.accessFlags)) {
        writerRefuse("override table isn't a public interface")
    }
    val remove = "$table->removeOverrideForParam(J)V"
    for (reference in updates.values + remove) {
        if (tableClass.methods.none { it.toString() == reference }) writerRefuse("override table doesn't declare its typed writer")
    }
    val removers = writerClass(store).methods.filter { method ->
        method.implementation?.instructions?.any { it.reference()?.toString() == remove } == true
    }
    val remover = removers.only("typed override remove")
    if (remover.implementation!!.instructions.count { it.reference()?.toString() == remove } != 1) writerRefuse("typed remove isn't a single call")

    // Behind the interface sits the native table, each typed put a plain hop to its native writer.
    val native = writerClass(TABLE_IMPL)
    if (!AccessFlags.PUBLIC.isSet(native.accessFlags) || table !in native.interfaces) {
        writerRefuse("native override table doesn't implement the store's table")
    }
    for ((value, writer) in OVERRIDE_VALUES) {
        val target = "$TABLE_IMPL->$writer(J$value)V"
        if (native.methods.none { it.toString() == target && it.isNativeInstance() }) writerRefuse("native table has no $writer")
        val bridge = native.methods.filter { it.toString() == "$TABLE_IMPL->updateOverrideForParam(J$value)V" }
            .only("native typed put for $value")
        val code = bridge.implementation?.instructions?.toList().orEmpty()
        if (code.size != 2 || code[0].opcode != Opcode.INVOKE_VIRTUAL || code[0].reference()?.toString() != target ||
            code[1].opcode != Opcode.RETURN_VOID) writerRefuse("native typed put doesn't go straight to $writer")
    }
    if (native.methods.none { it.toString() == "$TABLE_IMPL->removeOverrideForParam(J)V" && it.isNativeInstance() }) {
        writerRefuse("native table has no native remove")
    }

    // The session manager's table: the base class names it, the native manager makes the native table.
    val manager = writerClass(MANAGER_IMPL)
    val base = manager.superclass ?: writerRefuse("native manager has no base")
    val tableGetter = "$base->$TABLE_FACTORY()$table"
    if (writerClass(base).methods.none { it.toString() == tableGetter && it.isPublicInstance() }) writerRefuse("manager base has no table getter")
    val ownGetter = manager.methods.filter { it.name == TABLE_FACTORY && it.parameterTypes.isEmpty() && it.returnType == table }
        .only("native manager table getter")
    if (ownGetter.implementation?.instructions?.none { instruction ->
            instruction.reference()?.let { it.definingClass == MANAGER_IMPL && it.parameterTypes.isEmpty() && it.returnType == TABLE_IMPL } == true
        } != false) writerRefuse("native manager doesn't make the native table")

    // Instagram's store factory takes that table from the session manager's delegate.
    val delegate = storeFactory(store, model, base, tableGetter, putCode)
    val gates = writerClass(delegate.returnType).methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(Any::toString) == listOf(base) && method.returnType == MANAGER_IMPL
    }
    val gate = gates.only("native manager gate")
    if (gate.implementation?.instructions?.none { it.opcode == Opcode.INSTANCE_OF &&
            ((it as ReferenceInstruction).reference as? TypeReference)?.type == MANAGER_IMPL } != false) {
        writerRefuse("native manager gate doesn't check the native manager")
    }
    var gateUsers = 0
    classDefForEach { clazz ->
        if (clazz.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        gateUsers += clazz.methods.count { method ->
            RUNTIME_NOT_READY in method.texts() && method.implementation!!.instructions.any { it.reference()?.toString() == gate.toString() }
        }
    }
    if (gateUsers == 0) writerRefuse("no native-ready check uses the manager gate")
    writerStubs()
    return OverrideWriter(delegate.toString(), gate.toString(), tableGetter, table, updates, remove, decoder.toString())
}

/**
 * The one method that builds the debug store for a session. It asks the session [model] for its
 * manager delegate, asks that delegate for [tableGetter] and keeps the result in the field the
 * store's put reads. Returns the delegate accessor.
 */
private fun BytecodePatchContext.storeFactory(store: String, model: String, base: String, tableGetter: String,
                                              putCode: List<Instruction>): MethodReference {
    val factories = mutableListOf<Method>()
    classDefForEach { clazz ->
        if (clazz.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        clazz.methods.filterTo(factories) { method ->
            method.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE &&
                ((it as ReferenceInstruction).reference as? TypeReference)?.type == store } == true
        }
    }
    val factory = factories.only("override store factory")
    if (!AccessFlags.STATIC.isSet(factory.accessFlags) || factory.parameterTypes.map(Any::toString) != listOf(USER) ||
        factory.returnType != store) writerRefuse("override store factory doesn't build the store for a session")
    val code = factory.implementation!!.instructions.toList()
    val delegateIndex = code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_VIRTUAL && code[index].reference()?.let {
            it.definingClass == model && it.parameterTypes.isEmpty() && writerSuper(it.returnType) == base
        } == true
    }.only("session manager delegate call")
    val delegate = code[delegateIndex].reference()!!
    val owner = writerClass(model)
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || owner.methods.none { it.toString() == delegate.toString() && it.isPublicInstance() }) {
        writerRefuse("session manager delegate isn't public")
    }
    val delegateResult = (code.getOrNull(delegateIndex + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)
        ?.registerA ?: writerRefuse("session manager delegate isn't kept")
    val tableIndex = code.indices.filter { code[it].reference()?.toString() == tableGetter }.only("store factory table call")
    if (tableIndex <= delegateIndex + 1 || code[tableIndex].arguments().singleOrNull() != delegateResult) {
        writerRefuse("store factory asks another object for the table")
    }
    factory.requireStraight(code, delegateIndex + 1, tableIndex, delegateResult)
    val tableResult = (code.getOrNull(tableIndex + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)
        ?.registerA ?: writerRefuse("store factory doesn't keep the table")
    val kept = (code.getOrNull(tableIndex + 2)?.takeIf { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == tableResult }
        as? ReferenceInstruction)?.reference as? FieldReference ?: writerRefuse("store factory doesn't keep the table")
    if (putCode.none { it.opcode == Opcode.IGET_OBJECT && (it as ReferenceInstruction).reference.toString() == kept.toString() }) {
        writerRefuse("typed put reads another table")
    }
    return delegate
}

/** Nothing between the delegate's result and the table call may write it, leave, or be jumped into. */
private fun Method.requireStraight(code: List<Instruction>, from: Int, to: Int, register: Int) {
    val addresses = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> addresses[index + 1] = addresses[index] + instruction.codeUnits }
    fun inside(address: Int) = address > addresses[from] && address <= addresses[to]
    val leaving = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE, Opcode.THROW)
    for (index in from + 1 until to) {
        val instruction = code[index]
        if (instruction is OffsetInstruction || instruction.opcode in leaving) writerRefuse("store factory table flow isn't straight-line")
        val written = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        if (instruction.opcode.setsRegister() && (written == register || (instruction.opcode.setsWideRegister() && written + 1 == register))) {
            writerRefuse("store factory overwrites the delegate before the table call")
        }
    }
    for ((index, instruction) in code.withIndex()) {
        if (instruction !is OffsetInstruction) continue
        val target = addresses[index] + instruction.codeOffset
        if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
            val payload = addresses.indexOf(target).takeIf { it in code.indices }?.let { code[it] as? SwitchPayload }
                ?: writerRefuse("store factory has an unreadable switch")
            if (payload.switchElements.any { inside(addresses[index] + it.offset) }) writerRefuse("a switch enters the store factory table flow")
        } else if (inside(target)) writerRefuse("another branch enters the store factory table flow")
    }
    if (implementation!!.tryBlocks.any { block -> block.exceptionHandlers.any { inside(it.handlerCodeAddress) } }) {
        writerRefuse("an exception handler enters the store factory table flow")
    }
}

/**
 * Fills the writer stubs. Each setter refuses anything but the native table, then makes the same
 * typed call Instagram's editor makes and answers 1. The table stub answers null unless the
 * session's manager is the native one, so the Java table, whose writers throw, is never reached.
 */
internal fun BytecodePatchContext.fillOverrideWriter(writer: OverrideWriter, model: String) {
    val owner = mutableClassDefBy(OVERRIDE_BRIDGE)
    val stubs = writerStubs()
    fun setter(arguments: String, reference: String, vararg prelude: String) = listOf(
        "instance-of v0, p0, $TABLE_IMPL", "if-eqz v0, :unavailable", "check-cast p0, ${writer.table}",
    ) + prelude + listOf(
        "invoke-interface { $arguments }, $reference", "const/4 v0, 0x1", "return v0",
        ":unavailable", "const/4 v0, 0x0", "return v0",
    )
    val bodies = mapOf(
        "getOverrideTableNative" to (2 to listOf(
            "instance-of v0, p0, $model", "if-eqz v0, :unavailable", "check-cast p0, $model",
            "invoke-virtual { p0 }, ${writer.delegate}", "move-result-object v0", "if-eqz v0, :unavailable",
            "invoke-static { v0 }, ${writer.gate}", "move-result-object v1", "if-eqz v1, :unavailable",
            "invoke-virtual { v0 }, ${writer.tableGetter}", "move-result-object v0",
            "instance-of v1, v0, $TABLE_IMPL", "if-eqz v1, :unavailable", "return-object v0",
            ":unavailable", "const/4 v0, 0x0", "return-object v0",
        )),
        "setOverrideBooleanNative" to (2 to setter("p0, p1, p2, v1", writer.updates.getValue("Z"),
            "const/4 v1, 0x0", "if-eqz p3, :write", "const/4 v1, 0x1", ":write")),
        "setOverrideLongNative" to (1 to setter("p0, p1, p2, p3, p4", writer.updates.getValue("J"))),
        "setOverrideDoubleNative" to (1 to setter("p0, p1, p2, p3, p4", writer.updates.getValue("D"))),
        "setOverrideStringNative" to (1 to setter("p0, p1, p2, p3", writer.updates.getValue("Ljava/lang/String;"))),
        "removeOverrideNative" to (1 to setter("p0, p1, p2", writer.remove)),
        "getOverrideTypeNative" to (1 to listOf("invoke-static { p0, p1 }, ${writer.decoder}", "move-result v0", "return v0")),
    )
    val replacements = stubs.map { old ->
        val (locals, body) = bodies.getValue(old.name)
        val registers = old.parameterTypes.fold(locals) { count, type -> count + if (type.toString() == "J" || type.toString() == "D") 2 else 1 }
        ImmutableMethod(old.definingClass, old.name, old.parameters, old.returnType, old.accessFlags, old.annotations,
            old.hiddenApiRestrictions, ImmutableMethodImplementation(registers, emptyList(), null, null)).toMutable().apply {
            addInstructionsWithLabels(0, body.joinToString("\n"))
        }
    }
    stubs.forEach { owner.methods.remove(it) }
    owner.methods.addAll(replacements)
}

private fun BytecodePatchContext.writerStubs(): List<Method> {
    val bridge = writerClass(OVERRIDE_BRIDGE)
    return WRITER_STUBS.map { (name, shape) ->
        bridge.methods.filter { method ->
            method.name == name && method.parameterTypes.joinToString("") == shape.first &&
                method.returnType == shape.second && AccessFlags.STATIC.isSet(method.accessFlags)
        }.only("extension $name bridge")
    }
}

private fun BytecodePatchContext.writerClass(type: String): ClassDef = runCatching { classDefBy(type) }.getOrNull()
    ?: writerRefuse("missing native writer class")
private fun BytecodePatchContext.writerSuper(type: String): String? = runCatching { classDefBy(type) }.getOrNull()?.superclass
private fun BytecodePatchContext.publicStatic(reference: MethodReference) {
    val owner = writerClass(reference.definingClass)
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || owner.methods.none {
            it.toString() == reference.toString() && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        }) writerRefuse("override type decoder isn't public static")
}
private fun Method.isNativeInstance() = AccessFlags.NATIVE.isSet(accessFlags) && AccessFlags.PUBLIC.isSet(accessFlags) &&
    !AccessFlags.STATIC.isSet(accessFlags)
private fun Method.isPublicInstance() = AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags)
private fun Method.texts() = implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> writerRefuse("unreadable native writer call arguments")
}
private fun <T> List<T>.only(part: String): T = singleOrNull() ?: writerRefuse("expected one $part, found $size")
private fun writerRefuse(detail: String): Nothing = throw PatchException("Open developer options: $detail")
