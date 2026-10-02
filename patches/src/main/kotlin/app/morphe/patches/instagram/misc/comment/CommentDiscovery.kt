/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.ControlFlow
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.*

internal const val COMMENT_SELECT = "select_comment_screen_comment_select_tap_"
internal const val COMMENT_ROWS = "instagram_share_comment_to_story_entrypoint_impression"
internal const val COMMENT_LEGACY = "comment_options_menu_rendered"
internal const val COMMENT_NATIVE = "$EXTENSION_PACKAGE/comment/CommentMenuNative;"
internal const val COPY_ROW = "$EXTENSION_PACKAGE/comment/CopyRow;"
internal const val COPY_HOOK = "$EXTENSION_PACKAGE/comment/CommentCopy;->rows(Ljava/util/List;Ljava/lang/Object;Landroid/content/Context;)Ljava/util/List;"
internal const val FUNCTION = "Lkotlin/jvm/functions/Function0;"
internal const val OBJECT = "Ljava/lang/Object;"
internal const val STRING = "Ljava/lang/String;"
internal const val LIST = "Ljava/util/List;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val GIPHY = "Lcom/instagram/api/schemas/CommentGiphyMediaInfoIntf;"

/** All native boundaries, and spare registers, found before a single method is changed. */
internal data class CommentMenu(
    val renderer: Method, val at: Int, val rows: Int, val selected: Int, val context: Int,
    val spares: List<Int>, val text: FieldReference, val rowConstructor: MethodReference,
    val style: FieldReference, val iconConstructor: MethodReference, val labelConstructor: MethodReference,
    val callback: FieldReference, val icon: Int, val label: Int,
)

internal fun BytecodePatchContext.findCommentMenu(): CommentMenu {
    val classes = linkedMapOf<String, ClassDef>()
    classDefForEach { classes[it.type] = it }
    for (type in listOf(COMMENT_NATIVE, COPY_ROW, "$EXTENSION_PACKAGE/comment/CommentCopy;")) {
        if (type !in classes) refuse("missing extension boundary $type")
    }
    fun clazz(type: String) = classes[type] ?: refuse("missing native class $type")
    fun methods() = classes.values.asSequence().flatMap { it.methods.asSequence() }
    fun treeBacked(type: ClassDef): Boolean {
        var parent = type.superclass
        val seen = mutableSetOf<String>()
        while (parent != null && seen.add(parent)) {
            if (parent == "Lcom/facebook/pando/TreeJNI;") return true
            parent = classes[parent]?.superclass
        }
        return false
    }
    val select = methods().filter { COMMENT_SELECT in it.strings() && it.publicInstance() &&
        it.returnType == "V" && it.parameters() == listOf(STRING, STRING, "F", "Z") }
        .toList().one("selected-comment anchor")
    val selectedType = selectionType(select)
    val model = clazz(selectedType)
    requirePublic(model)
    val rawField = model.fields.filter { field ->
        classes[field.type]?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) &&
            it.methods.any { method -> method.parameterTypes.isEmpty() && method.returnType == GIPHY } } == true
    }.one("selected comment's raw model")
    val raw = clazz(rawField.type)
    val pando = classes.values.filter {
        raw.type in it.interfaces && treeBacked(it)
    }.one("comment's tree-backed model")
    val pandoText = pandoGetter("Copy comment", pando.type, "text", STRING)
    val getter = raw.methods.filter {
        it.name == pandoText.name && it.parameterTypes.isEmpty() && it.returnType == STRING
    }.one("original text interface getter")
    val valueModel = classes.values.filter { raw.type in it.interfaces && it.type != pando.type &&
        it.methods.any { method -> method.matches(getter) && method.code().any { instruction ->
            instruction.opcode == Opcode.IGET_OBJECT && instruction.field()?.type == STRING
        } } }.one("comment's parsed value model")
    val valueGetter = valueModel.methods.filter { it.matches(getter) }.one("parsed text getter")
    val valueText = parsedTextField(valueGetter, valueModel.type)
    val parser = methods().filter { method -> method.name == "unsafeParseFromJson" &&
        method.returnType in setOf(OBJECT, valueModel.type) && "text" in method.strings() &&
        method.code().any { it.call()?.let { call -> call.name == "<init>" && call.definingClass == valueModel.type } == true }
    }.toList().one("comment text parser")
    val keyAt = parser.code().indices.filter { (parser.code()[it].reference() as? StringReference)?.string == "text" }
        .one("parser's original text key")
    val readAt = (keyAt + 1 until minOf(keyAt + 8, parser.code().size)).filter {
        parser.code()[it].call()?.returnType == STRING && parser.code().getOrNull(it + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }.one("parser's original string read")
    if (constructorField(parser, readAt + 1, valueModel.type, classes, allowAbsent = true).toString() != valueText.toString()) {
        refuse("parsed text does not reach the original text getter")
    }
    val converter = methods().filter { it.returnType == selectedType && it.code().any { instruction ->
        instruction.call()?.let { call -> call.definingClass == raw.type && call.matches(getter) } == true
    } }.toList().one("selected-comment conversion")
    val textAt = converter.code().indices.filter { at ->
        converter.code()[at].call()?.let { it.definingClass == raw.type && it.matches(getter) } == true
    }.one("conversion's original text read")
    if (converter.code().getOrNull(textAt + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("conversion loses original text")
    val text = constructorField(converter, textAt + 1, selectedType, classes)
    requirePublicField(model, text)

    val builder = clazz(select.definingClass).methods.filter { COMMENT_ROWS in it.strings() }.one("comment row builder")
    if (builder.returnType != "Ljava/util/ArrayList;" || !select.code().any { it.call()?.matches(builder) == true }) {
        refuse("selection no longer calls the native row builder")
    }
    val rowConstructor = builder.code().mapNotNull { it.call() }.filter {
        it.name == "<init>" && it.returnType == "V" && it.parameters().size == 4 && it.parameters().last() == FUNCTION
    }.distinctBy { it.toString() }.one("native display row constructor")
    val rowBase = clazz(rowConstructor.definingClass)
    requirePublic(rowBase)
    if (AccessFlags.FINAL.isSet(rowBase.accessFlags) || rowBase.methods.any { AccessFlags.ABSTRACT.isSet(it.accessFlags) }) {
        refuse("native row cannot accept the independent Copy subtype")
    }
    requireConstructor(rowBase, rowConstructor.parameters())
    val callback = rowBase.fields.filter { it.type == FUNCTION && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .one("native row callback")
    requirePublicField(rowBase, callback)
    val styleBase = clazz(rowConstructor.parameters()[0])
    requirePublic(styleBase)
    val color = styleBase.fields.filter { it.type == INTEGER }.one("row text color")
    val style = builder.code().filter { it.opcode == Opcode.SGET_OBJECT }.mapNotNull { it.field() }.filter { field ->
        classes[field.type]?.let { styleClass ->
            styleClass.superclass == styleBase.type && styleClass.methods.any { method ->
                method.name == "<clinit>" && method.code().indices.any { at ->
                    val instruction = method.code()[at]
                    instruction.opcode == Opcode.IPUT_OBJECT && instruction.field()?.toString() == color.toString() &&
                        method.constantBefore(at, (instruction as TwoRegisterInstruction).registerA) == 0
                }
            }
        } == true
    }.distinctBy { it.toString() }.one("native normal style singleton")
    requirePublic(clazz(style.type))
    requirePublicField(clazz(style.definingClass), style)
    if (!AccessFlags.STATIC.isSet(clazz(style.definingClass).fields.single { it.name == style.name }.accessFlags)) {
        refuse("normal style is no longer static")
    }
    val iconClass = clazz(rowConstructor.parameters()[1])
    val labelClass = clazz(rowConstructor.parameters()[2])
    val iconConstructor = requireConstructor(iconClass, listOf("I"))
    val labelConstructor = requireConstructor(labelClass, listOf("I"))

    val renderer = methods().filter { method ->
        method.publicInstance() && method.returnType == "V" && method.parameters().let {
            it.size == 5 && it[1] == selectedType && it[3] == LIST && it[4] == "F"
        } && method.code().any { it.field()?.toString() == callback.toString() ||
            (it.reference() as? TypeReference)?.type == rowBase.type }
    }.toList().one("common comment menu renderer")
    // The stock renderer supplies its own callback wrapper. Its click consumes Function0,
    // and its boolean callback says to dismiss the popup, regardless of the row subtype.
    val wrapper = renderer.code().mapNotNull { it.call() }.filter {
        it.name == "<init>" && it.parameters() == listOf(OBJECT, "I")
    }.mapNotNull { classes[it.definingClass] }.distinctBy { it.type }.filter { type ->
        type.methods.any { method -> method.code().any { it.field()?.toString() == callback.toString() } &&
            method.code().any { it.call()?.toString() == "$FUNCTION->invoke()$OBJECT" } }
    }.one("stock comment row callback")
    val dismiss = wrapper.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }.one("stock dismissal decision")
    if (dismiss.code().map { it.opcode } != listOf(Opcode.CONST_4, Opcode.RETURN) ||
        (dismiss.code()[0] as NarrowLiteralInstruction).narrowLiteral != 1) refuse("stock row callback no longer dismisses")
    val listRegister = renderer.implementation!!.registerCount - 2
    val selectedRegister = renderer.implementation!!.registerCount - 4
    val code = renderer.code()
    if (code.any { it.call()?.toString() == COPY_HOOK }) refuse("renderer already carries the Copy hook")
    val at = code.indices.filter { index ->
        code[index].call()?.let { it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Iterable;") &&
            it.returnType == "I" } == true && code[index].arguments() == listOf(listRegister) &&
            code.getOrNull(index - 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            code.getOrNull(index - 2)?.call()?.let { it.name == "requireContext" &&
                it.returnType == "Landroid/content/Context;" } == true
    }.one("guarded renderer's list boundary")
    if (code.take(at).any { it.writes(listRegister) || it.writes(selectedRegister) }) refuse("renderer reuses its input parameters")
    if (code.take(at).count { it.opcode == Opcode.IF_NEZ } < 2) refuse("renderer lacks its duplicate-popup guards")
    val context = (code[at - 1] as OneRegisterInstruction).registerA
    val spare = try {
        renderer.getFreeRegisterProvider(at, 3, listRegister, selectedRegister, context).let {
            List(3) { _ -> it.getFreeRegister4Bit() }
        }
    } catch (failure: IllegalStateException) {
        refuse("renderer has no safe invocation registers: ${failure.message}")
    } catch (failure: IllegalArgumentException) {
        refuse("renderer has no safe invocation registers: ${failure.message}")
    }
    val copy = methods().filter { it.name == "toString" && it.returnType == STRING && "CopyText" in it.strings() }
        .toList().one("native CopyText label anchor")
    val copyCtor = requireConstructor(clazz(copy.definingClass), emptyList())
    val parentCallAt = copyCtor.code().indices.filter { index -> copyCtor.code()[index].call()?.let {
        it.name == "<init>" && it.parameters().let { types -> types.size == 4 &&
            types[1] == "I" && types[2] == "I" && types[3] == "Z" }
    } == true }.one("CopyText resource constructor")
    val parent = copyCtor.code()[parentCallAt].call()!!
    requireConstructor(clazz(parent.definingClass), parent.parameters())
    val arguments = copyCtor.code()[parentCallAt].arguments()
    val icon = copyCtor.constantBefore(parentCallAt, arguments[2]) ?: refuse("CopyText icon is not a constant")
    val label = copyCtor.constantBefore(parentCallAt, arguments[3]) ?: refuse("CopyText label is not a constant")
    if (icon ushr 24 != 0x7f || label ushr 24 != 0x7f || icon == label) refuse("CopyText resources are invalid")
    validateCommentStubs()
    return CommentMenu(renderer, at, listRegister, selectedRegister, context, spare, text, rowConstructor,
        style, iconConstructor, labelConstructor, callback, icon, label)
}

/** The selected comment, as resolved by the controller for the two comment IDs. */
internal fun selectionType(select: Method): String = select.code().mapNotNull { it.call() }.filter {
    it.parameters().size == 3 && it.parameters().takeLast(2) == listOf(STRING, STRING) &&
        it.returnType.startsWith("L") && it.returnType != STRING
}.distinctBy { it.toString() }.one("selected-comment resolver").returnType

/** Finding an unused field read isn't proof that the native getter returns the parsed text. */
private fun parsedTextField(getter: Method, type: String): FieldReference {
    if (!getter.publicInstance() || getter.parameterTypes.isNotEmpty() || getter.returnType != STRING) {
        refuse("parsed text getter changed shape")
    }
    val code = getter.code()
    val readAt = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT &&
        code[it].field()?.let { field -> field.definingClass == type && field.type == STRING } == true }
        .one("parsed original field read")
    val origins = textOrigins(getter, fieldAt = readAt, receiver = getter.implementation!!.registerCount - 1)
    val read = code[readAt] as TwoRegisterInstruction
    if (origins.before[readAt]?.get(read.registerB) != RECEIVER) refuse("parsed getter reads a different receiver")
    val returns = code.indices.filter { origins.before[it] != null &&
        code[it].opcode in setOf(Opcode.RETURN_OBJECT, Opcode.RETURN, Opcode.RETURN_WIDE) }
    if (returns.isEmpty() || returns.any { code[it].opcode != Opcode.RETURN_OBJECT ||
            origins.before[it]!![(code[it] as OneRegisterInstruction).registerA] != ORIGINAL }) {
        refuse("a parsed getter path substitutes or bypasses the original field")
    }
    return code[readAt].field()!!
}

/** The original read must supply the argument on every path, and that argument must supply the field. */
private fun constructorField(method: Method, resultAt: Int, type: String, classes: Map<String, ClassDef>,
                             allowAbsent: Boolean = false): FieldReference {
    val code = method.code()
    val origins = textOrigins(method, resultAt = resultAt)
    val candidates = code.indices.flatMap { at ->
        val call = code[at].call()
        val state = origins.before[at]
        if (call == null || call.definingClass != type || call.name != "<init>" || state == null) emptyList()
        else {
            val arguments = code[at].arguments()
            val slots = listOf(OBJECT) + call.parameters().flatMap { if (it == "J" || it == "D") listOf(it, "") else listOf(it) }
            if (arguments.size != slots.size || arguments.any { it !in state.indices }) refuse("invalid text constructor arguments")
            arguments.indices.filter { slots[it] == STRING && state[arguments[it]] and ORIGINAL != 0 }.map { at to it }
        }
    }
    val (at, argument) = candidates.one("original text constructor argument")
    val allowed = ORIGINAL or if (allowAbsent) ABSENT else 0
    if (origins.before[at]!![code[at].arguments()[argument]] and allowed.inv() != 0) {
        refuse("a path bypasses or replaces the original text read")
    }
    val call = code[at].call()!!
    val constructor = classes[type]?.methods?.filter { it.matches(call) }?.one("text model constructor")
        ?: refuse("missing text model constructor")
    val receiver = constructor.implementation!!.registerCount - code[at].arguments().size
    val fields = textOrigins(constructor, incoming = receiver + argument, receiver = receiver)
    val body = fields.flow.instructions
    val writes = body.indices.filter { body[it].opcode == Opcode.IPUT_OBJECT && fields.before[it] != null &&
        body[it].field()?.let { field -> field.definingClass == type && field.type == STRING } == true }
    val field = writes.filter { fields.before[it]!![(body[it] as TwoRegisterInstruction).registerA] and ORIGINAL != 0 }
        .map { body[it].field()!! }.distinctBy { it.toString() }.one("original text field")
    val stores = writes.filter { body[it].field()?.toString() == field.toString() }.toSet()
    if (stores.any { index ->
            val write = body[index] as TwoRegisterInstruction
            fields.before[index]!![write.registerA] != ORIGINAL || fields.before[index]!![write.registerB] != RECEIVER
        }) refuse("a constructor path substitutes the original text or its receiver")
    // An exception from a store has not assigned the field. Only its normal edge carries the write.
    val pending = java.util.ArrayDeque<Int>().apply { add(0) }
    val seen = mutableSetOf<Int>()
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (!seen.add(index)) continue
        if (body[index].opcode == Opcode.RETURN_VOID) refuse("a constructor path bypasses the original field assignment")
        pending.addAll(fields.flow.exceptional[index])
        if (index !in stores) pending.addAll(fields.flow.normal[index])
    }
    return field
}

private const val UNKNOWN = 1
private const val ORIGINAL = 2
private const val ABSENT = 4
private const val RECEIVER = 8
private val objectMoves = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val constants = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)
private data class TextOrigins(val flow: ControlFlow, val before: Array<IntArray?>)

/** Finite origin sets joined across every branch and pre-write exception edge. */
private fun textOrigins(method: Method, resultAt: Int? = null, incoming: Int? = null, receiver: Int? = null,
                        fieldAt: Int? = null): TextOrigins {
    val flow = try { ControlFlow.of(method) } catch (failure: IllegalArgumentException) {
        refuse("unsupported text control flow: ${failure.message}")
    } catch (failure: ClassCastException) {
        refuse("unsupported text control flow: ${failure.message}")
    }
    if (flow.instructions.isEmpty()) refuse("empty text control flow")
    if (resultAt != null && (flow.normal.indices.any { it != resultAt - 1 && resultAt in flow.normal[it] } ||
        flow.exceptional.any { resultAt in it })) refuse("a path bypasses the original getter before its result")
    val before = arrayOfNulls<IntArray>(flow.instructions.size)
    val start = IntArray(method.implementation!!.registerCount) { UNKNOWN }
    incoming?.let { start[it] = ORIGINAL }
    receiver?.let { start[it] = RECEIVER }
    before[0] = start
    val pending = java.util.ArrayDeque<Int>().apply { add(0) }
    fun merge(at: Int, state: IntArray) {
        val previous = before[at]
        if (previous == null) {
            before[at] = state.clone()
            pending.add(at)
        } else {
            var changed = false
            for (register in previous.indices) {
                val joined = previous[register] or state[register]
                if (joined != previous[register]) { previous[register] = joined; changed = true }
            }
            if (changed) pending.add(at)
        }
    }
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        val state = before[at]!!.clone()
        val instruction = flow.instructions[at]
        val after = state.clone()
        val destination = (instruction as? OneRegisterInstruction)?.registerA
        if (instruction.opcode.setsRegister() && destination != null) {
            if (destination !in state.indices || instruction.opcode.setsWideRegister() && destination + 1 !in state.indices) {
                refuse("invalid register in original text flow")
            }
            after[destination] = when {
                at == resultAt || at == fieldAt -> ORIGINAL
                instruction.opcode in objectMoves -> state[(instruction as TwoRegisterInstruction).registerB]
                instruction.opcode == Opcode.CHECK_CAST -> state[destination]
                instruction.opcode in constants && (instruction as NarrowLiteralInstruction).narrowLiteral == 0 -> ABSENT
                else -> UNKNOWN
            }
            if (instruction.opcode.setsWideRegister()) after[destination + 1] = UNKNOWN
        }
        flow.normal[at].forEach { merge(it, after) }
        flow.exceptional[at].forEach { merge(it, state) }
    }
    return TextOrigins(flow, before)
}

private fun requireConstructor(type: ClassDef, parameters: List<String>): Method {
    requirePublic(type)
    return type.methods.filter { it.name == "<init>" && it.publicInstance() && it.parameters() == parameters }
        .one("public constructor on ${type.type}")
}
private fun requirePublic(type: ClassDef) {
    if (!AccessFlags.PUBLIC.isSet(type.accessFlags)) refuse("native type isn't public: ${type.type}")
}
private fun requirePublicField(type: ClassDef, field: FieldReference) {
    if (type.fields.none { it.name == field.name && it.type == field.type && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
        refuse("native field isn't public: $field")
    }
}
internal fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
internal fun Method.strings(): List<String> = code().mapNotNull { (it.reference() as? StringReference)?.string }
internal fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
internal fun Instruction.call() = reference() as? MethodReference
internal fun Instruction.field() = reference() as? FieldReference
internal fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is Instruction35c -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}
internal fun MethodReference.parameters() = parameterTypes.map(Any::toString)
internal fun MethodReference.matches(other: MethodReference) =
    name == other.name && returnType == other.returnType && parameters() == other.parameters()
private fun Method.publicInstance() = AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags)
private fun Method.constantBefore(at: Int, register: Int): Int? =
    code().take(at).lastOrNull { it.writes(register) }?.let { it as? NarrowLiteralInstruction }?.narrowLiteral
private fun Instruction.writes(register: Int) = opcode.setsRegister() &&
    (this as? OneRegisterInstruction)?.let { it.registerA == register || opcode.setsWideRegister() && it.registerA + 1 == register } == true
private fun <T> Collection<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
internal fun refuse(detail: String): Nothing = throw PatchException("Copy comment: $detail")
