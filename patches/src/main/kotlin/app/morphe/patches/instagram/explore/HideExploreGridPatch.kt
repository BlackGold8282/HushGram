/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Hide the Explore grid"
internal const val HIDE_GRID = "$EXTENSION_PACKAGE/explore/ExploreGrid;->hide(Ljava/util/List;)Z"

/** The keys of the topical Explore page the parser reads: its sections, and whether there's more. */
internal const val SECTIONS = "sectional_items"
internal const val MORE_AVAILABLE = "more_available"
internal const val AUTO_LOAD_MORE = "auto_load_more_enabled"

/** A string only the topical Explore page's parser holds beside [SECTIONS]. */
internal const val PAGING_TOKEN = "session_paging_token"

private val FIELD_WRITES = setOf(
    Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT,
)

/**
 * Empties the Search tab's Explore grid. Off in the default selection, as Hide the Reels tab is:
 * Explore is one of Instagram's main screens, so taking it away is the user's pick.
 */
@Suppress("unused")
val hideExploreGridPatch = bytecodePatch(
    name = "Hide the Explore grid",
    description = "Empties the grid of posts and reels under the Search tab's bar. Search, your recent searches and " +
        "search results stay.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("exploreGrid")
        emptyExplorePages(findExploreParser())
        enableStatus("exploreGrid")
    }
}

/**
 * The topical Explore page's JSON parser: the class and method, the page's type and register, the
 * index of the return that hands the page back, and the page fields the hook writes.
 */
internal class ExploreParser(
    val type: String,
    val parameters: List<String>,
    val page: String,
    val register: Int,
    val returnAt: Int,
    val sections: FieldReference,
    val moreAvailable: FieldReference,
    val autoLoadMore: FieldReference,
)

/**
 * Finds the one `unsafeParseFromJson` holding [SECTIONS] and [PAGING_TOKEN], and in it the fields
 * the page's sections and its two "more" flags are read into. Fails when there isn't exactly one
 * such parser, or it reads them some other way, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findExploreParser(): ExploreParser {
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.name != "unsafeParseFromJson") return@forEach
            val strings = method.strings()
            if (SECTIONS in strings && PAGING_TOKEN in strings) found += classDef.type to method
        }
    }
    val (type, method) = found.singleOrNull()
        ?: refuse("expected one Explore page parser holding $SECTIONS and $PAGING_TOKEN, found ${found.size}")
    val code = method.implementation!!.instructions.toList()
    val where = "$type->${method.name}"

    fun fieldAfter(key: String, opcode: Opcode, fieldType: String): Pair<FieldReference, Int> {
        val at = code.indexOfFirst { (it as? ReferenceInstruction)?.reference.let { r -> r is StringReference && r.string == key } }
        if (at < 0) refuse("$where doesn't read $key")
        val write = code.drop(at + 1).firstOrNull { it.opcode in FIELD_WRITES }
            ?: refuse("$where reads $key into nothing")
        val field = (write as ReferenceInstruction).reference as FieldReference
        if (write.opcode != opcode || field.type != fieldType) {
            refuse("$where reads $key into ${field.definingClass}->${field.name}:${field.type}, not a $fieldType")
        }
        return field to (write as TwoRegisterInstruction).registerB
    }

    val (sections, register) = fieldAfter(SECTIONS, Opcode.IPUT_OBJECT, "Ljava/util/List;")
    val page = sections.definingClass
    val (moreAvailable, moreOn) = fieldAfter(MORE_AVAILABLE, Opcode.IPUT_BOOLEAN, "Z")
    val (autoLoadMore, autoOn) = fieldAfter(AUTO_LOAD_MORE, Opcode.IPUT_BOOLEAN, "Z")
    for ((field, on) in listOf(moreAvailable to moreOn, autoLoadMore to autoOn)) {
        if (field.definingClass != page || on != register) refuse("$where writes ${field.name} on something other than its page")
    }
    val returns = code.withIndex().filter {
        it.value.opcode == Opcode.RETURN_OBJECT && (it.value as OneRegisterInstruction).registerA == register
    }
    val returnAt = returns.singleOrNull()?.index ?: refuse("$where hands its page back ${returns.size} times, expected once")
    if (register > 15) refuse("$where keeps its page in v$register, out of reach of a field write")
    return ExploreParser(
        type, method.parameterTypes.map(CharSequence::toString), page, register, returnAt,
        sections, moreAvailable, autoLoadMore,
    )
}

/**
 * At the parser's return, asks [HIDE_GRID] with the page's sections, and on a yes gives the page an
 * empty section list and turns off its "more" flags, so nothing loads more on its own. The cursor
 * stays: without one Instagram leaves Explore on its loading placeholder for good, and with it the
 * empty page shows a load more button whose page comes back empty too. The hook sits on the
 * return's own label, since the parser's loop branches straight to it.
 */
internal fun BytecodePatchContext.emptyExplorePages(parser: ExploreParser) {
    val method = mutableClassDefBy(parser.type).methods.single {
        it.name == "unsafeParseFromJson" && it.parameterTypes.map(CharSequence::toString) == parser.parameters
    }
    val page = parser.register
    val temp = (0..15).first { it != page }
    fun ref(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"
    method.addInstructionsAtControlFlowLabel(
        parser.returnAt,
        """
            iget-object v$temp, v$page, ${ref(parser.sections)}
            invoke-static { v$temp }, $HIDE_GRID
            move-result v$temp
            if-eqz v$temp, :keep
            new-instance v$temp, Ljava/util/ArrayList;
            invoke-direct { v$temp }, Ljava/util/ArrayList;-><init>()V
            iput-object v$temp, v$page, ${ref(parser.sections)}
            const/4 v$temp, 0x0
            iput-boolean v$temp, v$page, ${ref(parser.moreAvailable)}
            iput-boolean v$temp, v$page, ${ref(parser.autoLoadMore)}
            :keep
            nop
        """,
    )
}

private fun Method.strings(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    ?.toSet() ?: emptySet()

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
