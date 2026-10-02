/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.util.extendsClass
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val STORY_SEEN = "$EXTENSION_PACKAGE/stories/StorySeen;"
internal const val TO_SEND = "$STORY_SEEN->toSend(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val STORY_SEEN_BUTTON = "$EXTENSION_PACKAGE/stories/StorySeenButton;"
internal const val BIND_BUTTON = "$STORY_SEEN_BUTTON->bind(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"

/** Classes Instagram keeps the names of: the account signed in and a story in the viewer. */
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
internal const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"

/** The name the seen request sends the stories you watched under. */
internal const val REELS_KEY = "reels"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val MAP = "Ljava/util/Map;"
private const val VIEW = "Landroid/view/View;"

/** What a batch may hold its stories in, so the extension can read it as a Map. */
private val MAP_TYPES = setOf(MAP, "Ljava/util/HashMap;", "Ljava/util/LinkedHashMap;")

/** The collections a new batch may start with, empty, in what the seen request sends. */
private val FRESH_TYPES = setOf("Ljava/util/HashMap;", "Ljava/util/LinkedHashMap;", "Ljava/util/ArrayList;")

private val IGETS = setOf(
    Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT,
)
private val IPUTS = setOf(
    Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT,
)
private val ZERO_CONSTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Everything View stories anonymously works on, found before anything changes.
 *
 * The batch is the class whose method builds the seen request; the store is the class that sends a
 * batch, [send] the one method doing it, and [getter] the store's static getter for an account.
 * [reels] is the batch's map of the stories it holds, the one the request writes under "reels". The
 * header binder hands the hook its account, story and view holder, parameters [session], [item]
 * and [holder], and [itemView] is the view holder's root view.
 */
internal class StorySeenTargets(
    val batch: String,
    val store: String,
    val send: String,
    val getter: String,
    val reels: FieldReference,
    val binder: String,
    val binderName: String,
    val binderParameters: List<String>,
    val session: Int,
    val item: Int,
    val holder: Int,
    val itemView: FieldReference,
    internal val stubs: StorySeenStubs,
)

/** The extension methods the patch hooks with or fills in, found before anything changes. */
internal class StorySeenStubs(
    val emptyBatch: MutableMethod,
    val seenStories: MutableMethod,
    val sendBatch: MutableMethod,
    val storyId: MutableMethod,
    val itemView: MutableMethod,
)

/**
 * Finds what the patch needs and checks it's safe to write, or refuses naming what's wrong:
 *
 * - the store's one send, an instance method taking the batch that builds the seen request from
 *   the batch it's handed, with nothing written over that parameter before the request is built;
 * - the batch's map of stories: the seen request names [REELS_KEY] once and adds, under it, what a
 *   static `(Map)String` made of one map field of its own batch, read on a straight run from there;
 * - that a batch the extension starts holds nothing the seen request sends: the request reads only
 *   fields of its own batch and hands the batch to nothing, and the public constructor taking
 *   nothing, with the constructors it runs, starts each of those fields as a new empty collection
 *   or as nothing at all, and the stories as a new empty map;
 * - the store's one public static getter taking a [USER_SESSION], so a tap can send through it;
 * - the story header binder, static, taking one [USER_SESSION], one [REEL_ITEM] and one view
 *   holder, whose first instruction no branch lands on, with three locals the hook can borrow;
 * - the view holder base's item view, the public View field its constructor keeps its parameter in,
 *   and [REEL_ITEM]'s public getId();
 * - the extension's hooks and stubs.
 */
internal fun BytecodePatchContext.findStorySeen(): StorySeenTargets {
    val request = uniqueMethod(PATCH, "story seen request", StorySeenRequestFingerprint)
    val batch = request.definingClass
    val store = uniqueMethod(PATCH, "pending story seen store", PendingStorySeenStoreFingerprint).definingClass
    val storeClass = classDefByOrNull(store) ?: refuse("$store isn't in this build")
    val batchClass = classDefByOrNull(batch) ?: refuse("$batch isn't in this build")

    val senders = storeClass.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(Any::toString) == listOf(batch) &&
            method.code().any { it.methodReference()?.sameAs(request) == true }
    }
    val send = senders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one instance method ($batch)V in $store that builds the seen request, found ${senders.size}",
    )
    val sendCode = send.code()
    val builds = sendCode.indices.filter { sendCode[it].methodReference()?.sameAs(request) == true }
    val build = builds.singleOrNull() ?: refuse("expected $store->${send.name} to build the seen request once, found ${builds.size}")
    if (sendCode[build].namedRegisters().firstOrNull() != send.parameterRegisterNumber(0)) {
        refuse("$store->${send.name} builds the seen request from something other than the batch it's handed")
    }
    send.requireParameterIntact(PATCH, 0, listOf(build))

    val reels = storiesField(request, batch)
    requireFreshBatchIsEmpty(request, batchClass, reels)

    val getters = storeClass.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(Any::toString) == listOf(USER_SESSION) && method.returnType == store
    }
    val getter = getters.singleOrNull()
        ?: refuse("expected $store to have one public static getter taking a $USER_SESSION, found ${getters.size}")
    if (!AccessFlags.PUBLIC.isSet(storeClass.accessFlags) || !AccessFlags.PUBLIC.isSet(send.accessFlags)) {
        refuse("$store->${send.name} isn't public, so a tap can't send through it")
    }

    val binder = uniqueMethod(PATCH, "story header binder", StoryHeaderBinderFingerprint)
    val where = "${binder.definingClass}->${binder.name}"
    if (!AccessFlags.STATIC.isSet(binder.accessFlags)) refuse("$where isn't static, as the story header binder is")
    val parameters = binder.parameterTypes.map(Any::toString)
    val holderInit = uniqueMethod(PATCH, "view holder constructor", ViewHolderFingerprint)
    val base = holderInit.definingClass
    fun one(what: String, matches: (String) -> Boolean): Int {
        val found = parameters.indices.filter { matches(parameters[it]) }
        return found.singleOrNull() ?: refuse("expected $where to take one $what, found ${found.size}")
    }
    val session = one(USER_SESSION) { it == USER_SESSION }
    val item = one(REEL_ITEM) { it == REEL_ITEM }
    val holder = one("view holder") { it.startsWith("L") && extendsClass(it, base) }
    if (0 in binder.jumpTargets()) refuse("a branch in $where lands on its first instruction, where the hook goes")
    val locals = binder.localRegisterCount()
    if (locals < 3) refuse("$where has $locals local register(s), needs 3")

    val itemView = itemViewField(holderInit)
    val reelItem = classDefByOrNull(REEL_ITEM) ?: refuse("$REEL_ITEM isn't in this build")
    reelItem.methods.singleOrNull {
        it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == STRING &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$REEL_ITEM has no public getId()")
    listOf(batchClass, storeClass, reelItem, classDefByOrNull(base)!!).forEach { reachable ->
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) refuse("${reachable.type} isn't public, so the extension can't reach it")
    }

    return StorySeenTargets(
        batch, store, send.name, getter.name, reels, binder.definingClass, binder.name, parameters,
        session, item, holder, itemView, storySeenStubs(),
    )
}

/**
 * The batch's map of stories: the seen request names [REELS_KEY] once, and the very next call adds
 * a String under it, which a static `(Map)String` made straight before from a map field of `this`.
 * No branch lands between that read and the call, so it's the only way the value gets there.
 */
private fun storiesField(request: Method, batch: String): FieldReference {
    val where = "$batch->${request.name}"
    if (AccessFlags.STATIC.isSet(request.accessFlags)) refuse("$where, the seen request, is static")
    val code = request.code()
    val named = code.indices.filter { code[it].string() == REELS_KEY }
    val at = named.singleOrNull() ?: refuse("expected $where to name \"$REELS_KEY\" once, found ${named.size}")
    val key = (code[at] as OneRegisterInstruction).registerA
    val put = code.getOrNull(at + 1)
    val added = put?.methodReference()
    val putRegisters = put?.namedRegisters().orEmpty()
    if (put == null || added == null || put.opcode != Opcode.INVOKE_VIRTUAL || added.returnType != "V" ||
        added.parameterTypes.map(Any::toString) != listOf(STRING, STRING) || putRegisters.size != 3 || putRegisters[1] != key
    ) {
        refuse("$where doesn't add the stories under \"$REELS_KEY\" right after naming them")
    }
    val value = putRegisters[2]
    val made = (at - 1 downTo 0).firstOrNull { code[it].writes(value) }
        ?.takeIf { code[it].opcode == Opcode.MOVE_RESULT_OBJECT }
        ?: refuse("$where doesn't make what it sends under \"$REELS_KEY\" from a map")
    val serializer = code.getOrNull(made - 1)
    val serialized = serializer?.methodReference()
    if (serializer == null || serialized == null || serializer.opcode != Opcode.INVOKE_STATIC ||
        serialized.parameterTypes.map(Any::toString) != listOf(MAP) || serialized.returnType != STRING
    ) {
        refuse("$where doesn't make what it sends under \"$REELS_KEY\" from a map")
    }
    val map = serializer.namedRegisters().single()
    val read = (made - 2 downTo 0).firstOrNull { code[it].writes(map) }
    val field = read?.let { code[it].fieldReference() }
    if (read == null || field == null || code[read].opcode != Opcode.IGET_OBJECT || field.definingClass != batch ||
        field.type !in MAP_TYPES || (code[read] as TwoRegisterInstruction).registerB != request.localRegisterCount()
    ) {
        refuse("$where doesn't read the stories it sends under \"$REELS_KEY\" from a map of its own batch")
    }
    if (request.jumpTargets().any { it in read + 1..at + 1 }) {
        refuse("a branch in $where lands between its read of the stories and where it adds them under \"$REELS_KEY\"")
    }
    request.requireThisIntact(PATCH, listOf(read))
    return field
}

/**
 * Refuses unless a batch made by the public `<init>()V` holds nothing the seen request sends, so a
 * batch the extension starts and gives only the stories you marked sends those and nothing else.
 */
private fun BytecodePatchContext.requireFreshBatchIsEmpty(request: Method, batchClass: ClassDef, reels: FieldReference) {
    val batch = batchClass.type
    val where = "$batch->${request.name}"
    val code = request.code()
    val self = request.localRegisterCount()
    val reads = code.indices.filter { code[it].opcode in IGETS && code[it].fieldReference()?.definingClass == batch }
    code.forEachIndexed { index, instruction ->
        if (self !in instruction.namedRegisters()) return@forEachIndexed
        if (index !in reads || (instruction as TwoRegisterInstruction).registerB != self) {
            refuse("$where hands its batch on at instruction $index, so what it sends can't be told from the batch's fields")
        }
    }
    request.requireThisIntact(PATCH, reads)
    val sent = reads.map { code[it].fieldReference()!! }.distinctBy { it.toString() }

    val start = batchClass.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.isEmpty() }
    if (start == null || !AccessFlags.PUBLIC.isSet(start.accessFlags)) {
        refuse("$batch has no public constructor taking nothing, so the extension can't start a batch of its own")
    }
    val constructors = mutableListOf<Method>()
    var next: List<Method> = listOf(start)
    while (next.isNotEmpty()) {
        if (constructors.size > 8) refuse("$batch's constructors call each other more than the patch follows")
        constructors += next
        next = next.flatMap { constructor -> chained(constructor, batchClass) }.filter { it !in constructors }
    }

    val written = mutableMapOf<String, MutableList<Boolean>>()
    for (constructor in constructors) {
        val body = constructor.code()
        val own = constructor.localRegisterCount()
        val jumps = constructor.jumpTargets()
        val named = body.indices.filter { own in body[it].namedRegisters() }
        constructor.requireThisIntact(PATCH, named)
        for (index in named) {
            val instruction = body[index]
            val called = instruction.methodReference()
            val field = instruction.fieldReference()
            val chains = instruction.opcode in setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE) &&
                called?.name == "<init>" && (called.definingClass == batch || called.definingClass == batchClass.superclass) &&
                instruction.namedRegisters().first() == own
            val onSelf = (instruction.opcode in IPUTS || instruction.opcode in IGETS) && field != null &&
                (instruction as TwoRegisterInstruction).registerB == own
            if (!chains && !onSelf) {
                refuse("$batch's constructor hands the batch on at instruction $index, so a new batch may not start empty")
            }
            if (instruction.opcode !in IPUTS || field == null || sent.none { it.toString() == field.toString() }) continue
            val stored = (instruction as TwoRegisterInstruction).registerA
            val fresh = when (freshValue(body, index, stored, jumps)) {
                Fresh.NOTHING -> false
                Fresh.EMPTY -> true
                null -> refuse(
                    "$batch's constructor starts ${field.name}, which the seen request sends, as something other " +
                        "than a new empty collection or nothing",
                )
            }
            written.getOrPut(field.toString()) { mutableListOf() } += fresh
        }
    }
    val stories = written[reels.toString()]
    if (stories.isNullOrEmpty() || !stories.all { it }) {
        refuse("$batch's constructor doesn't start ${reels.name}, its stories, as a new empty map")
    }
}

/** The constructors of [batchClass] that [constructor] runs on its own `this`. */
private fun chained(constructor: Method, batchClass: ClassDef): List<Method> {
    val own = constructor.localRegisterCount()
    return constructor.code().mapNotNull { instruction ->
        val called = instruction.methodReference() ?: return@mapNotNull null
        if (called.definingClass != batchClass.type || called.name != "<init>" || instruction.namedRegisters().firstOrNull() != own) {
            return@mapNotNull null
        }
        batchClass.methods.singleOrNull { it.name == "<init>" && it.sameAs(called) }
    }
}

private enum class Fresh { NOTHING, EMPTY }

/**
 * What the [register] that instruction [at] stores holds: nothing (a zero loaded on a straight run
 * to it), or a new empty collection the register holds and does nothing else with, or neither.
 */
private fun freshValue(code: List<Instruction>, at: Int, register: Int, jumps: Set<Int>): Fresh? {
    val loaded = (at - 1 downTo 0).firstOrNull { code[it].writes(register) } ?: return null
    val load = code[loaded]
    if (load.opcode in ZERO_CONSTS && (load as NarrowLiteralInstruction).narrowLiteral == 0) {
        return if (jumps.none { it in loaded + 1..at }) Fresh.NOTHING else null
    }
    if (load.opcode != Opcode.NEW_INSTANCE) return null
    val type = ((load as ReferenceInstruction).reference as TypeReference).type
    if (type !in FRESH_TYPES) return null
    val uses = code.indices.filter { register in code[it].namedRegisters() }
    val writes = uses.filter { code[it].writes(register) }
    val starts = uses.filter { index ->
        val called = code[index].methodReference()
        code[index].opcode == Opcode.INVOKE_DIRECT && called?.definingClass == type && called.name == "<init>" &&
            called.parameterTypes.isEmpty() && code[index].namedRegisters() == listOf(register)
    }
    val stores = uses.filter { code[it].opcode in IPUTS && (code[it] as TwoRegisterInstruction).registerA == register }
    if (writes != listOf(loaded) || starts.size != 1 || starts.single() < loaded || uses.toSet() != setOf(loaded) + starts + stores) {
        return null
    }
    return Fresh.EMPTY
}

/** The public View field of the view holder base that its constructor stores its one parameter in. */
private fun BytecodePatchContext.itemViewField(constructor: Method): FieldReference {
    val base = constructor.definingClass
    val code = constructor.code()
    val view = constructor.parameterRegisterNumber(0)
    val own = constructor.localRegisterCount()
    val stores = code.indices.filter { index ->
        val field = code[index].fieldReference()
        code[index].opcode == Opcode.IPUT_OBJECT && field?.definingClass == base && field.type == VIEW &&
            (code[index] as TwoRegisterInstruction).let { it.registerA == view && it.registerB == own }
    }
    val fields = stores.map { code[it].fieldReference()!! }.distinctBy { it.toString() }
    val field = fields.singleOrNull() ?: refuse("expected $base's constructor to keep its item view in one field, found ${fields.size}")
    constructor.requireParameterIntact(PATCH, 0, stores)
    constructor.requireThisIntact(PATCH, stores)
    val declared = classDefByOrNull(base)?.fields?.singleOrNull { it.name == field.name && it.type == VIEW }
    if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
        refuse("$base->${field.name}, the item view, isn't a public field the extension can read")
    }
    return field
}

private fun BytecodePatchContext.storySeenStubs(): StorySeenStubs {
    val seen = classDefByOrNull(STORY_SEEN)?.let { mutableClassDefBy(STORY_SEEN) } ?: refuse("$STORY_SEEN isn't in the extension")
    val button = classDefByOrNull(STORY_SEEN_BUTTON)?.let { mutableClassDefBy(STORY_SEEN_BUTTON) }
        ?: refuse("$STORY_SEEN_BUTTON isn't in the extension")
    fun stub(owner: String, methods: Iterable<MutableMethod>, name: String, parameters: List<String>, returns: String) =
        methods.singleOrNull {
            it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(Any::toString) == parameters
        } ?: refuse("$owner has no static $returns $name(${parameters.joinToString("")})")
    for ((owner, methods, hook) in listOf(Triple(STORY_SEEN, seen.methods, TO_SEND), Triple(STORY_SEEN_BUTTON, button.methods, BIND_BUTTON))) {
        methods.singleOrNull {
            "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook.substringAfter("->") &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: refuse("$owner has no public static hook ${hook.substringAfter("->")}")
    }
    val emptyBatch = stub(STORY_SEEN, seen.methods, "emptyBatch", emptyList(), OBJECT)
    if (emptyBatch.localRegisterCount() < 1) refuse("$STORY_SEEN->emptyBatch() has no local register to start a batch in")
    return StorySeenStubs(
        emptyBatch = emptyBatch,
        seenStories = stub(STORY_SEEN, seen.methods, "seenStories", listOf(OBJECT), MAP),
        sendBatch = stub(STORY_SEEN, seen.methods, "send", listOf(OBJECT, OBJECT), "V"),
        storyId = stub(STORY_SEEN_BUTTON, button.methods, "storyId", listOf(OBJECT), STRING),
        itemView = stub(STORY_SEEN_BUTTON, button.methods, "itemView", listOf(OBJECT), VIEW),
    )
}

/**
 * Puts the hook first in the send: the extension answers the batch to send, Instagram's own when
 * nothing is held back, one of its own holding only the stories you marked, or null, and the send
 * returns before building anything on null. The answer takes the batch's place, so the send's
 * empty check and the request are both made from it.
 */
internal fun BytecodePatchContext.hookStorySend(found: StorySeenTargets) {
    val send = mutableClassDefBy(found.store).methods.single {
        it.name == found.send && it.parameterTypes.map(Any::toString) == listOf(found.batch) && it.returnType == "V"
    }
    val batch = send.parameterRegister(0)
    send.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { $batch .. $batch }, $TO_SEND
            move-result-object $batch
            if-nez $batch, :send
            return-void
            :send
            check-cast $batch, ${found.batch}
        """,
    )
}

/**
 * Puts the button's hook first in the story header binder, handing it the account signed in, the
 * story and its view holder in three borrowed locals: nothing is in them before the binder's own
 * first instruction, and the parameters may sit past v15.
 */
internal fun BytecodePatchContext.hookStoryHeader(found: StorySeenTargets) {
    val binder = mutableClassDefBy(found.binder).methods.single {
        it.name == found.binderName && it.parameterTypes.map(Any::toString) == found.binderParameters
    }
    binder.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${binder.parameterRegister(found.session)}
            move-object/from16 v1, ${binder.parameterRegister(found.item)}
            move-object/from16 v2, ${binder.parameterRegister(found.holder)}
            invoke-static { v0, v1, v2 }, $BIND_BUTTON
        """,
    )
}

/** Fills the extension's stubs in. Each answers on one path, so none joins two ways at one return. */
internal fun StorySeenTargets.fillStubs() {
    stubs.emptyBatch.addInstructionsWithLabels(
        0,
        """
            new-instance v0, $batch
            invoke-direct { v0 }, $batch-><init>()V
            return-object v0
        """,
    )
    stubs.seenStories.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $batch
            iget-object p0, p0, $reels
            return-object p0
        """,
    )
    stubs.sendBatch.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $USER_SESSION
            invoke-static { p0 }, $store->$getter($USER_SESSION)$store
            move-result-object p0
            check-cast p1, $batch
            invoke-virtual { p0, p1 }, $store->$send($batch)V
            return-void
        """,
    )
    stubs.storyId.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $REEL_ITEM
            invoke-virtual { p0 }, $REEL_ITEM->getId()$STRING
            move-result-object p0
            return-object p0
        """,
    )
    stubs.itemView.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${itemView.definingClass}
            iget-object p0, p0, $itemView
            return-object p0
        """,
    )
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun MethodReference.sameAs(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}
