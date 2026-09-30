/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedReelsHookTest {
    private val parser = "Lfixture/FeedItemParser;"
    private val item = "Lfixture/FeedItem;"
    private val kind = "Lfixture/FeedItemKind;"
    private val fetch = "Lfixture/FetchReason;"
    private val jsonParser = "Lfixture/JsonParser;"

    /** The hook the patch writes is in the FeedReels the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val type = FILTER.substringBefore("->")
        val declared = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FILTER is not in the extension: $declared", FILTER.substringAfter("->") in declared)
    }

    /** The parse helper's answer goes through the filter and is cast back; the other helper stays. */
    @Test
    fun theParseHelperAnswersThroughTheFilter() {
        val context = PatchContexts.of(classes())

        context.filterParsedFeedItems()

        val patched = context.mutableClassDefBy(item)
        assertFilteredBeforeReturn("helper", patched.methods.single { it.name == "A02" })
        assertEquals("the other static helper was touched", 2, patched.methods.single { it.name == "A01" }.instructions().size)
    }

    @Test
    fun aKindEnumMissingAReelsUnitFailsThePatch() {
        val context = PatchContexts.of(classes(kindNames = listOf("MEDIA", "CLIPS_NETEGO")))
        assertThrows(PatchException::class.java) { context.filterParsedFeedItems() }
    }

    @Test
    fun anotherEnumNamingAReelsUnitFailsThePatch() {
        val context = PatchContexts.of(classes(fetchNames = listOf("COLD_START", "VIBES_IN_FEED_UNIT")))
        assertThrows(PatchException::class.java) { context.filterParsedFeedItems() }
    }

    @Test
    fun twoParseHelpersFailThePatch() {
        val context = PatchContexts.of(classes(helpers = 2))
        assertThrows(PatchException::class.java) { context.filterParsedFeedItems() }
    }

    /**
     * In each declared build the parser makes one class with a ClipsNetego field, its one static
     * helper parsing from JSON answers through the filter, and one of its enums names every unit.
     */
    @Test
    fun eachDeclaredBuildFiltersTheParsedFeedItem() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, "clips_netego")
                val made = holders.flatMap { it.methods }.flatMap { it.instructions() }
                    .filter { it.opcode == Opcode.NEW_INSTANCE }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                    .toSet()
                val candidates = FixtureDex.classes(bundle, made)
                val itemType = candidates.values.single { classDef -> classDef.fields.any { it.type == CLIPS_NETEGO } }.type
                val fieldTypes = candidates.getValue(itemType).fields.map { it.type }.toSet()
                val classes = (holders + candidates.values + FixtureDex.classes(bundle, fieldTypes).values).distinctBy { it.type }
                val context = PatchContexts.of(classes)

                context.filterParsedFeedItems()

                val before = classes.single { it.type == itemType }.methods.single { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.instructions().any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "parseFromJsonParser"
                    }
                }
                val after = context.mutableClassDefBy(itemType).methods.single {
                    it.name == before.name && it.parameterTypes.map(Any::toString) == before.parameterTypes.map(Any::toString)
                }
                val returns = before.instructions().count { it.opcode == Opcode.RETURN_OBJECT }
                assertEquals("${bundle.name}: helper size", before.instructions().size + 3 * returns, after.instructions().size)
                assertFilteredBeforeReturn(bundle.name, after)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun assertFilteredBeforeReturn(what: String, helper: Method) {
        val code = helper.instructions()
        val at = code.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT }
        assertEquals(
            "$what: the filter's opcodes",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.RETURN_OBJECT),
            code.subList(at - 3, at + 1).map { it.opcode },
        )
        assertEquals("$what: the filter called", FILTER, (code[at - 3] as ReferenceInstruction).reference.toString())
        assertEquals(
            "$what: the cast",
            helper.returnType,
            ((code[at - 1] as ReferenceInstruction).reference as TypeReference).type,
        )
        val returned = (code[at] as OneRegisterInstruction).registerA
        assertEquals("$what: the register cast", returned, (code[at - 1] as OneRegisterInstruction).registerA)
    }

    /**
     * Stand-ins shaped like Instagram 449's: the parser, the feed item with its ClipsNetego field and
     * two enum fields, the item's static helpers (one parsing from JSON, one wrapping a post) and the
     * two enums with the names their static initializers load.
     */
    private fun classes(
        kindNames: List<String> = listOf("MEDIA", "AD") + REEL_UNITS,
        fetchNames: List<String> = listOf("COLD_START", "PULL_TO_REFRESH"),
        helpers: Int = 1,
    ): List<ClassDef> {
        val public = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val static = public or AccessFlags.STATIC.value
        fun method(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, code: List<Instruction>) =
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, code, null, null),
            )
        fun enum(type: String, names: List<String>) = ImmutableClassDef(
            type, public or AccessFlags.ENUM.value, "Ljava/lang/Enum;", null, null, null, null,
            listOf(
                method(
                    type, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 1,
                    names.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )
        val parse = listOf(
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(parser, "A00", parser)),
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0,
                ImmutableMethodReference(parser, "parseFromJsonParser", listOf(jsonParser), "Ljava/lang/Object;"),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(item)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        val itemMethods = (1..helpers).map { method(item, "A0${it + 1}", listOf(jsonParser), item, static, 2, parse) } +
            method(
                item, "A01", listOf("Lcom/instagram/feed/media/Media;"), item, static, 2,
                listOf(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(item)), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
            )
        val itemClass = ImmutableClassDef(
            item, public, "Ljava/lang/Object;", null, null, null,
            listOf(
                ImmutableField(item, "A03", CLIPS_NETEGO, AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField(item, "A0r", kind, AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField(item, "A0s", fetch, AccessFlags.PUBLIC.value, null, null, null),
            ),
            itemMethods,
        )
        val parserClass = ImmutableClassDef(
            parser, public, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                method(
                    parser, "unsafeParseFromJson", listOf(jsonParser), "Ljava/lang/Object;",
                    public or AccessFlags.BRIDGE.value or AccessFlags.SYNTHETIC.value, 3,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("media_or_ad")),
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("clips_netego")),
                        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(item)),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ),
                ),
            ),
        )
        return listOf(parserClass, itemClass, enum(kind, kindNames), enum(fetch, fetchNames))
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    @Suppress("unused")
    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
