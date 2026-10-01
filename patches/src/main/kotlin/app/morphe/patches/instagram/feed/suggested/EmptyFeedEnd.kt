/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val FEED_ENDED = "$EXTENSION_PACKAGE/feed/FeedSuggestions;->feedEnded(I)I"

/** The log tag of the home feed adapter's model builder, and the key of the loading row it adds. */
internal const val BUILD_MODELS = "MainfeedAdapter.buildModels"
internal const val SHIMMER_KEY = "shimmer"

/**
 * The home feed adapter and the flag it reads to tell an empty feed that's finished (no next page)
 * from one still loading.
 */
internal class FeedEnd(val adapter: String, val flag: FieldReference)

/**
 * Finds the one method holding [BUILD_MODELS] and [SHIMMER_KEY]. With the feed empty it adds a
 * loading row keyed [SHIMMER_KEY], unless the feed has no next page, nothing is loading and there's
 * nothing else to show, when it adds Instagram's own empty feed card instead. The flag is the last
 * boolean field it reads before that key, right before asking the same object whether it's empty, which
 * it asks again just before adding the loading row.
 */
internal fun BytecodePatchContext.findFeedEnd(): FeedEnd {
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.holds(BUILD_MODELS) && method.holds(SHIMMER_KEY)) found += classDef.type to method
        }
    }
    val (adapter, method) = found.singleOrNull()
        ?: refuse("expected one method holding $BUILD_MODELS and $SHIMMER_KEY, found ${found.size}")
    val code = method.implementation!!.instructions.toList()
    val where = "$adapter->${method.name}"
    val shimmer = code.indexOfFirst { (it as? ReferenceInstruction)?.reference.let { r -> r is StringReference && r.string == SHIMMER_KEY } }
    val read = (shimmer - 1 downTo 0).firstOrNull { code[it].opcode == Opcode.IGET_BOOLEAN }
        ?: refuse("$where reads no flag before its loading row")
    val owner = (code[read] as TwoRegisterInstruction).registerB
    val asked = code.getOrNull(read + 2)
    val empty = (asked as? ReferenceInstruction)?.reference as? MethodReference
    if (asked?.opcode != Opcode.INVOKE_VIRTUAL || (asked as FiveRegisterInstruction).registerC != owner ||
        empty?.returnType != "Z" || empty.parameterTypes.isNotEmpty()
    ) {
        refuse("$where doesn't ask its feed whether it's empty after reading the flag")
    }
    val again = (read + 3 until shimmer).any { code[it].calls(empty) }
    if (!again) refuse("$where adds its loading row without asking whether the feed is empty")
    return FeedEnd(adapter, (code[read] as ReferenceInstruction).reference as FieldReference)
}

/**
 * Passes each read of the flag in the home feed adapter through [FEED_ENDED], which says the feed
 * has no next page once Hide suggested posts has taken items out. Instagram still checks that the
 * feed is empty and that nothing is loading, so a feed with posts left, or one waiting on a page,
 * keeps what it draws. An emptied feed then gets Instagram's own empty feed card instead of its
 * loading placeholder, which it would keep for good: nothing asks for the next page of an empty feed.
 */
internal fun BytecodePatchContext.endEmptiedFeed(end: FeedEnd) {
    var hooked = 0
    mutableClassDefBy(end.adapter).methods.forEach { method ->
        val code = method.implementation?.instructions?.toList() ?: return@forEach
        code.indices.filter { code[it].opcode == Opcode.IGET_BOOLEAN && code[it].reads(end.flag) }.reversed().forEach { at ->
            val flag = (code[at] as TwoRegisterInstruction).registerA
            method.addInstructions(
                at + 1,
                """
                    invoke-static/range { v$flag .. v$flag }, $FEED_ENDED
                    move-result v$flag
                """,
            )
            hooked++
        }
    }
    if (hooked == 0) refuse("${end.adapter} never reads ${end.flag.name}")
}

private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
} == true

private fun Instruction.calls(method: MethodReference) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == method.toString()

private fun Instruction.reads(field: FieldReference) =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == field.toString()

private fun refuse(detail: String): Nothing = throw PatchException("Hide suggested posts: $detail")
