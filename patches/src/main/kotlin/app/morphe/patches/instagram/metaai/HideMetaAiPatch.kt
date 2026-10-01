/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.feed.filterParsedFeedItems
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Hide Meta AI"
private const val META_AI = "$EXTENSION_PACKAGE/metaai/MetaAi;"
internal const val SEARCH_FLAG = "$META_AI->searchFlag(I)Z"
internal const val META_AI_FILTER = "$META_AI->filter(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val FOLLOW_UP_BAR = "$META_AI->followUpBar(Landroid/view/View;)Landroid/view/View;"

/** Two strings only the search results page's bottom bar setup holds on 449. */
internal val FOLLOW_UP_SETUP = listOf("keyboardHeightChangeDetector", "bottomSearchSuggestionPillsHelper")
private const val FIND_VIEW = "Landroid/view/View;->findViewById(I)Landroid/view/View;"
private const val INFLATE = "Landroid/view/ViewStub;->inflate()Landroid/view/View;"
private const val VIEW_STUB = "Landroid/view/ViewStub;"

/** How far before the stub's lookup the check that it's there may come: 449 puts one call between them. */
private const val CHECK_WITHIN = 6

/**
 * The server flags that decide whether a search bar offers Meta AI on Instagram 449: the Search
 * tab's, which also covers its results and Meta AI's answers there, the messages inbox's "ask Meta
 * AI" bar, and the Meta AI ring at the end of the inbox's bar.
 */
internal val SEARCH_FLAGS = listOf(0x81068600111f6bL, 0x8104190006113aL, 0x810417001b1130L)

/** Meta AI's feed item kinds: Vibes videos, Meta AI chats and Imagine pictures of you. */
internal val META_AI_UNITS = listOf("VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT", "MEMU_IN_FEED_UNIT")

/** How far after a flag's id its read may come: 449 puts at most a cast between them. */
private const val READ_WITHIN = 4

@Suppress("unused")
val hideMetaAiPatch = bytecodePatch(
    name = "Hide Meta AI",
    description = "Takes Meta AI out of the search bars, in the Search tab and at the top of your messages, so they " +
        "search the plain way, drops the Ask a follow-up bar under search results, and removes Meta AI's posts " +
        "from your home feed. Each has its own switch, and the search one shows once Instagram restarts.",
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        // Every read is found and checked, and SettingsStatus is confirmed to carry the switch's
        // method, before any hook changes an instruction.
        requireStatusMethod("metaAi")
        val reads = findSearchFlagReads()
        val followUp = findFollowUpBarCheck()
        filterParsedFeedItems(PATCH, META_AI_FILTER, META_AI_UNITS)
        answerSearchFlagReads(reads)
        dropFollowUpBar(followUp)
        enableStatus("metaAi")
    }
}

/** A read of one of [SEARCH_FLAGS]: the method, and the index and register of its move-result. */
internal class FlagRead(
    val flag: Long,
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val moveResult: Int,
    val register: Int,
)

/**
 * Every place the app loads one of [SEARCH_FLAGS] and reads it as a boolean: the id goes straight
 * into a call taking it last and answering a boolean, whose move-result follows. Fails when a flag
 * is never read, or is loaded for any other use, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findSearchFlagReads(): List<FlagRead> {
    val reads = mutableListOf<FlagRead>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            code.forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.CONST_WIDE) return@forEachIndexed
                val flag = (instruction as WideLiteralInstruction).wideLiteral
                if (flag !in SEARCH_FLAGS) return@forEachIndexed
                val where = "${classDef.type}->${method.name} loads ${flag.toString(16)}"
                val id = (instruction as OneRegisterInstruction).registerA
                val callAt = (index + 1..minOf(index + READ_WITHIN, code.lastIndex))
                    .firstOrNull { code[it].methodReference() != null }
                    ?: refuse("$where with no call after it")
                val call = code[callAt].methodReference()!!
                val passed = code[callAt].arguments()
                if (passed.takeLast(2) != listOf(id, id + 1) || call.parameterTypes.lastOrNull()?.toString() != "J") {
                    refuse("$where and then calls ${call.definingClass}->${call.name} without it last")
                }
                if (call.returnType != "Z") refuse("$where for ${call.definingClass}->${call.name}, which answers ${call.returnType}")
                val result = code.getOrNull(callAt + 1)
                if (result?.opcode != Opcode.MOVE_RESULT) refuse("$where and drops the answer")
                reads += FlagRead(
                    flag, classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
                    callAt + 1, (result as OneRegisterInstruction).registerA,
                )
            }
        }
    }
    val unread = SEARCH_FLAGS.filter { flag -> reads.none { it.flag == flag } }
    if (unread.isNotEmpty()) refuse("nothing reads ${unread.joinToString { it.toString(16) }}")
    return reads
}

/** Passes each read's answer through MetaAi.searchFlag, right after its move-result. */
internal fun BytecodePatchContext.answerSearchFlagReads(reads: List<FlagRead>) {
    reads.groupBy { Triple(it.type, it.name, it.parameters) }.forEach { (_, inMethod) ->
        val first = inMethod.first()
        val method = mutableClassDefBy(first.type).methods.single {
            it.name == first.name && it.returnType == first.returnType &&
                it.parameterTypes.map(CharSequence::toString) == first.parameters
        }
        inMethod.sortedByDescending { it.moveResult }.forEach { read ->
            method.addInstructions(
                read.moveResult + 1,
                """
                    invoke-static/range { v${read.register} .. v${read.register} }, $SEARCH_FLAG
                    move-result v${read.register}
                """,
            )
        }
    }
}

/** Where the search results page checks its bottom bar's stub is there: the method, and the index and register of the check's move-result. */
internal class StubCheck(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val moveResult: Int,
    val register: Int,
)

/**
 * The check, in the search results page's bottom bar setup (the one method holding
 * [FOLLOW_UP_SETUP]), that the bar's stub is in the page: a findViewById whose answer is tested for
 * null before a static lookup of the same id hands the stub to inflate(). A null there leaves the
 * page without the bar, the way it is when the layout has no stub, and every later use of the bar
 * checks for that. The pills inside the bar come from a second stub, found inside the bar through a
 * findViewById and a cast, which is never inflated once the bar isn't.
 */
internal fun BytecodePatchContext.findFollowUpBarCheck(): StubCheck {
    val setups = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            val strings = method.implementation?.instructions
                ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                ?: return@forEach
            if (strings.containsAll(FOLLOW_UP_SETUP)) setups += classDef to method
        }
    }
    val (classDef, method) = setups.singleOrNull()
        ?: refuse("${setups.size} methods hold ${FOLLOW_UP_SETUP.joinToString(" and ")}, not one")
    val where = "${classDef.type}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val lookups = code.indices.filter { index ->
        val call = code[index].methodReference() ?: return@filter false
        code[index].opcode == Opcode.INVOKE_STATIC && call.returnType == VIEW_STUB &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "I") &&
            code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            code.getOrNull(index + 2)?.methodReference()?.toString() == INFLATE
    }
    val lookup = lookups.singleOrNull() ?: refuse("$where looks up a stub to inflate ${lookups.size} times, not once")
    val find = (lookup - 1 downTo maxOf(0, lookup - CHECK_WITHIN))
        .firstOrNull { code[it].methodReference()?.toString() == FIND_VIEW }
        ?: refuse("$where inflates its stub without checking it's there")
    if (code[find].arguments() != code[lookup].arguments()) refuse("$where checks one view and inflates another")
    val result = code[find + 1]
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where drops the check's answer")
    val register = (result as OneRegisterInstruction).registerA
    val test = code[find + 2]
    if (test.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't test the stub for null")
    }
    return StubCheck(
        classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        find + 1, register,
    )
}

/** Passes the stub check's answer through MetaAi.followUpBar, right after its move-result. */
internal fun BytecodePatchContext.dropFollowUpBar(check: StubCheck) {
    val method = mutableClassDefBy(check.type).methods.single {
        it.name == check.name && it.returnType == check.returnType &&
            it.parameterTypes.map(CharSequence::toString) == check.parameters
    }
    method.addInstructions(
        check.moveResult + 1,
        """
            invoke-static/range { v${check.register} .. v${check.register} }, $FOLLOW_UP_BAR
            move-result-object v${check.register}
        """,
    )
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The registers an invoke passes, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
