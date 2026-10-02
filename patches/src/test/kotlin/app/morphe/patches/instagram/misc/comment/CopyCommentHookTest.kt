/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.*
import org.junit.Assert.*
import org.junit.Test

class CopyCommentHookTest {
    private val public = AccessFlags.PUBLIC.value
    private val static = public or AccessFlags.STATIC.value
    private val iface = public or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value
    private val context = "Landroid/content/Context;"
    private val giphy = "Lcom/instagram/api/schemas/CommentGiphyMediaInfoIntf;"

    @Test fun renamedNativeModelsResolveOriginalTextAndOnlyTheGuardedRendererChanges() {
        for (salt in listOf("First", "Renamed")) {
            val native = nativeClasses(salt)
            val patch = PatchContexts.of(native + extension())
            val menu = patch.findCommentMenu()
            val unchangedTypes = native.map { it.type }.filter { it != menu.renderer.definingClass }
            val unchanged = snapshot(patch, unchangedTypes)
            assertEquals("original$salt", menu.text.name)
            assertEquals(0x7f080123, menu.icon)
            assertEquals(0x7f130456, menu.label)
            patch.applyCommentMenu(menu)
            assertWiring(patch, menu)
            assertEquals("stock model, builder and dismissal methods stay intact", unchanged, snapshot(patch, unchangedTypes))
        }
    }

    @Test fun missingAmbiguousPrivateOrChangedBoundariesRefuseBeforeAnyMutation() {
        val valid = nativeClasses("First")
        val select = valid.single { it.type.endsWith("Controller;") }
        val cases = mapOf(
            "missing selector" to valid.filter { it != select },
            "ambiguous selector" to valid + clazz("Ltest/Duplicate;", methods = listOf(method("Ltest/Duplicate;",
                "other", listOf(STRING, STRING, "F", "Z"), "V", 5, public, "const-string v0, \"$COMMENT_SELECT\"\nreturn-void"))),
            "parser changed" to nativeClasses("First", textKey = "text_translation"),
            "private text" to nativeClasses("First", textFlags = AccessFlags.PRIVATE.value),
            "private row constructor" to nativeClasses("First", rowFlags = AccessFlags.PRIVATE.value),
            "final native row" to nativeClasses("First", rowTypeFlags = public or AccessFlags.FINAL.value),
            "private resource wrapper" to nativeClasses("First", labelFlags = AccessFlags.PRIVATE.value),
            "private style base" to nativeClasses("First", styleTypeFlags = AccessFlags.ABSTRACT.value),
            "non-dismissing callback" to nativeClasses("First", dismiss = 0),
            "missing popup guards" to nativeClasses("First", guards = false),
            "missing bridge" to valid + extension().filter { it.type != COMMENT_NATIVE },
        )
        for ((case, native) in cases) {
            val classes = if (native.any { it.type == COPY_ROW }) native else native + extension()
            // Missing-bridge case deliberately omits it, rather than restoring the valid extension.
            val actual = if (case == "missing bridge") native else classes
            val patch = PatchContexts.of(actual)
            val before = snapshot(patch, actual.map { it.type })
            val failure = runCatching { patch.applyCommentMenu(patch.findCommentMenu()) }.exceptionOrNull()
            assertTrue("$case: $failure", failure?.message?.startsWith("Copy comment: ") == true)
            assertEquals(case, before, snapshot(patch, actual.map { it.type }))
        }
    }

    @Test fun wideWriteToTheOriginalParametersHighHalfRefusesBeforeMutation() {
        refusesFlow(nativeClasses("Wide", constructorFlow = "wide"))
    }

    @Test fun branchCannotBypassTheConstructorOriginalAlias() {
        refusesFlow(nativeClasses("Branch", constructorFlow = "branch"))
    }

    @Test fun branchCannotEnterTheConverterAfterTheOriginalRead() {
        refusesFlow(nativeClasses("Entry", converterFlow = "branch"))
    }

    @Test fun exceptionHandlerCannotBypassTheConverterOriginalRead() {
        refusesFlow(nativeClasses("Handler", converterFlow = "handler"))
    }

    @Test fun validObjectAliasesAndConvergingBranchesKeepTheOriginalText() {
        val patch = PatchContexts.of(nativeClasses("Alias", constructorFlow = "joined", converterFlow = "alias") + extension())
        val menu = patch.findCommentMenu()
        assertEquals("originalAlias", menu.text.name)
        patch.applyCommentMenu(menu)
        assertWiring(patch, menu)
    }

    @Test fun parsedGetterCannotReplaceItsReturnAfterLoadingThePlainField() {
        refusesFlow(nativeClasses("GetterReturn", getterFlow = "overwrite"))
    }

    @Test fun parsedGetterCannotSubstituteItsReturnOnABranch() {
        refusesFlow(nativeClasses("GetterBranch", getterFlow = "branch"))
    }

    @Test fun parsedGetterMustReadTheOriginalReceiver() {
        refusesFlow(nativeClasses("GetterReceiver", getterFlow = "receiver"))
    }

    private fun refusesFlow(native: List<ClassDef>) {
        val classes = native + extension()
        val patch = PatchContexts.of(classes)
        val before = snapshot(patch, classes.map { it.type })
        val failure = runCatching { patch.applyCommentMenu(patch.findCommentMenu()) }.exceptionOrNull()
        assertTrue("unsafe original-text flow: $failure", failure?.message?.startsWith("Copy comment: ") == true)
        assertEquals("unsafe flow refuses before mutation", before, snapshot(patch, classes.map { it.type }))
    }

    @Test fun everyDeclaredFixtureSuppliesTheParserModelRendererAndStockDismissal() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            val anchors = (FixtureDex.classesHolding(bundle, COMMENT_SELECT) +
                FixtureDex.classesHolding(bundle, "CopyText") + FixtureDex.classesHolding(bundle, COMMENT_LEGACY))
                .distinctBy { it.type }
            val selects = anchors.flatMap { it.methods }.filter { COMMENT_SELECT in it.strings() &&
                it.parameters() == listOf(STRING, STRING, "F", "Z") && it.returnType == "V" }
            assertEquals("fixture selector count", 1, selects.size)
            val select = selects.single()
            val selected = selectionType(select)
            val model = FixtureDex.classes(bundle, setOf(selected)).getValue(selected)
            val interfaces = FixtureDex.classes(bundle, model.fields.map { it.type }.toSet()).values
            val raws = interfaces.filter { type -> type.methods.any {
                it.parameterTypes.isEmpty() && it.returnType == giphy
            } && AccessFlags.INTERFACE.isSet(type.accessFlags) }
            assertEquals("fixture raw interfaces: ${raws.map { it.type }}", 1, raws.size)
            val raw = raws.single().type
            val parts = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                dex.classes.forEach { type ->
                    if (raw in type.interfaces || type.methods.any { method ->
                            method.returnType == selected || method.parameters().let {
                                it.size == 5 && it[1] == selected && it[3] == LIST && it[4] == "F"
                            }
                        }) parts += ImmutableClassDef.of(type)
                }
            }
            val values = parts.filter { raw in it.interfaces && it.superclass != "Lcom/facebook/pando/TreeJNI;" }.map { it.type }.toSet()
            FixtureDex.forEach(bundle) { dex ->
                dex.classes.forEach { type ->
                    if (type.methods.any { method -> method.name == "unsafeParseFromJson" && "text" in method.strings() &&
                        method.code().any { it.call()?.let { call -> call.name == "<init>" && call.definingClass in values } == true } }) {
                        parts += ImmutableClassDef.of(type)
                    }
                }
            }
            val core = (anchors + model + interfaces + parts).distinctBy { it.type }
            val dependencies = core.mapNotNull { it.superclass }.toMutableSet()
            for (type in core) for (method in type.methods) for (instruction in method.code()) {
                when (val reference = instruction.reference()) {
                    is MethodReference -> {
                        dependencies += reference.definingClass
                        dependencies += reference.returnType
                        dependencies += reference.parameters()
                    }
                    is FieldReference -> { dependencies += reference.definingClass; dependencies += reference.type }
                    is TypeReference -> dependencies += reference.type
                }
            }
            val classes = (core + FixtureDex.classes(bundle, dependencies).values).distinctBy { it.type }
            val patch = PatchContexts.of(classes + extension())
            val legacy = classes.filter { type -> type.methods.any { COMMENT_LEGACY in it.strings() } }.map { it.type }
            assertTrue("fixture lost the separate legacy surface", legacy.isNotEmpty())
            val unchanged = snapshot(patch, legacy)
            val menu = patch.findCommentMenu()
            patch.applyCommentMenu(menu)
            assertWiring(patch, menu)
            assertEquals("legacy surface must not be reported as patched", unchanged, snapshot(patch, legacy))
            checked += version
        }
        assertEquals("declared build has no fixture", versions, checked)
    }

    private fun assertWiring(patch: BytecodePatchContext, menu: CommentMenu) {
        val renderer = patch.mutableClassDefBy(menu.renderer.definingClass).methods.single { it.matches(menu.renderer) }
        assertEquals(1, renderer.code().count { it.call()?.toString() == COPY_HOOK })
        val callAt = renderer.code().indexOfFirst { it.call()?.toString() == COPY_HOOK }
        assertTrue("hook must follow the popup guards", renderer.code().take(callAt).count { it.opcode == Opcode.IF_NEZ } >= 2)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, renderer.code()[callAt + 1].opcode)
        assertEquals(menu.rowConstructor.definingClass, patch.mutableClassDefBy(COPY_ROW).superclass)
        val bridge = patch.mutableClassDefBy(COMMENT_NATIVE).methods.single { it.name == "originalText" }
        assertTrue(bridge.code().any { it.field()?.toString() == menu.text.toString() })
        assertEquals("separate unsupported/text returns avoid an ART object merge", 2,
            bridge.code().count { it.opcode == Opcode.RETURN_OBJECT })
        val factory = patch.mutableClassDefBy(COMMENT_NATIVE).methods.single { it.name == "newRow" }
        assertTrue(factory.code().any { it.field()?.toString() == menu.style.toString() })
        assertTrue(factory.code().any { it.call()?.toString() == menu.labelConstructor.toString() })
        assertTrue(factory.code().any { (it.reference() as? TypeReference)?.type == COPY_ROW })
    }

    private fun snapshot(patch: BytecodePatchContext, types: Collection<String>) = types.associateWith { type ->
        patch.mutableClassDefBy(type).let { clazz -> clazz.superclass to clazz.methods.map { method ->
            method.toString() to (method.implementation?.registerCount to method.code().map { instruction ->
                listOf(instruction.opcode, instruction.reference(), instruction.arguments(),
                    (instruction as? com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)?.registerA,
                    (instruction as? com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction)?.registerB,
                    (instruction as? com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction)?.narrowLiteral,
                    (instruction as? com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction)?.codeOffset)
            })
        }.sortedBy { it.first } }
    }
    private fun extension() = listOf(COMMENT_NATIVE, COPY_ROW, COPY_HOOK.substringBefore("->")).map(ExtensionDex::classDef)

    private fun nativeClasses(salt: String, textKey: String = "text", textFlags: Int = public,
                              rowFlags: Int = public, dismiss: Int = 1, guards: Boolean = true,
                              rowTypeFlags: Int = public or AccessFlags.ABSTRACT.value, labelFlags: Int = public,
                              constructorFlow: String = "alias", converterFlow: String = "direct",
                              getterFlow: String = "alias", styleTypeFlags: Int = public or AccessFlags.ABSTRACT.value): List<ClassDef> {
        fun t(name: String) = "Ltest/$salt$name;"
        val raw = t("Raw")
        val value = t("Value")
        val view = t("View")
        val row = t("Row")
        val style = t("Style")
        val normal = t("Normal")
        val icon = t("Icon")
        val label = t("Label")
        val controller = t("Controller")
        val renderer = t("Renderer")
        val wrapper = t("Callback")
        fun field(owner: String, name: String, type: String, flags: Int = public) =
            ImmutableField(owner, name, type, flags, null, null, null)
        fun constructor(owner: String, parameters: List<String>, body: String, flags: Int = public) =
            method(owner, "<init>", parameters, "V", parameters.size + 1, flags, body)
        val getterBody = when (getterFlow) {
            "overwrite" -> "iget-object v0, p0, $value->plain:$STRING\nconst-string v0, \"translated\"\nreturn-object v0"
            "branch" -> "iget-object v0, p0, $value->plain:$STRING\nif-eqz v0, :done\nconst-string v0, \"translated\"\n:done\nreturn-object v0"
            "receiver" -> "const/4 v1, 0x0\niget-object v0, v1, $value->plain:$STRING\nreturn-object v0"
            else -> "iget-object v0, p0, $value->plain:$STRING\nmove-object v1, v0\ncheck-cast v1, $STRING\nreturn-object v1"
        }
        val originalAlias = when (constructorFlow) {
            "wide" -> "const-wide/16 p1, 0x0\nmove-object v0, p2"
            "branch" -> "const-string v0, \"translated\"\nif-eqz p1, :store\nmove-object v0, p2\n:store"
            "joined" -> "if-eqz p1, :other\nmove-object v0, p2\ngoto :store\n:other\nmove-object v0, p2\n:store\ncheck-cast v0, $STRING"
            else -> "move-object v0, p2"
        }
        val beforeRead = if (converterFlow == "branch")
            "const-string v2, \"translated\"\nif-eqz p0, :build" else if (converterFlow == "handler")
            "const-string v2, \"translated\"" else ""
        val afterRead = when (converterFlow) {
            "branch" -> ":build"
            "handler" -> "goto :build\nmove-exception v1\n:build"
            "alias" -> "move-object v1, v2\nconst/4 v2, 0x0"
            else -> ""
        }
        var conversion = method(t("Converter"), "convert", listOf(raw), view, 5, static, """
            $beforeRead
            invoke-interface { p0 }, $raw->original()$STRING
            move-result-object v2
            $afterRead
            new-instance v0, $view
            const-string v3, "translated"
            invoke-direct { v0, p0, ${if (converterFlow == "alias") "v1" else "v2"}, v3 }, $view-><init>($raw$STRING$STRING)V
            return-object v0
        """)
        if (converterFlow == "handler") {
            val code = conversion.code()
            val read = code.indexOfFirst { it.call()?.definingClass == raw }
            val handler = code.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
            fun address(at: Int) = code.take(at).sumOf { it.codeUnits }
            conversion = ImmutableMethod(conversion.definingClass, conversion.name, conversion.parameters,
                conversion.returnType, conversion.accessFlags, null, null,
                ImmutableMethodImplementation(5, code, listOf(ImmutableTryBlock(address(read), code[read].codeUnits,
                    listOf(ImmutableExceptionHandler("Ljava/lang/Exception;", address(handler))))), null))
        }
        return listOf(
            clazz(raw, flags = iface, methods = listOf(
                ImmutableMethod(raw, "original", emptyList(), STRING, iface, null, null, null),
                ImmutableMethod(raw, "gif", emptyList(), giphy, iface, null, null, null))),
            clazz(t("TreeBase"), superclass = "Lcom/facebook/pando/TreeJNI;"),
            clazz(t("Pando"), superclass = t("TreeBase"), interfaces = listOf(raw), methods = listOf(
                method(t("Pando"), "original", emptyList(), STRING, 2, public,
                    "const v0, ${"text".hashCode()}\nconst/4 v0, 0x0\nreturn-object v0"))),
            clazz(value, interfaces = listOf(raw), fields = listOf(field(value, "plain", STRING), field(value, "translation", STRING)), methods = listOf(
                method(value, "original", emptyList(), STRING, 3, public, getterBody),
                constructor(value, listOf(STRING, STRING), "iput-object p1, p0, $value->plain:$STRING\niput-object p2, p0, $value->translation:$STRING\nreturn-void"))),
            clazz(view, fields = listOf(field(view, "raw", raw), field(view, "original$salt", STRING, textFlags), field(view, "translated$salt", STRING)),
                methods = listOf(method(view, "<init>", listOf(raw, STRING, STRING), "V", 6, public, """
                    iput-object p1, p0, $view->raw:$raw
                    $originalAlias
                    iput-object v0, p0, $view->original$salt:$STRING
                    iput-object p3, p0, $view->translated$salt:$STRING
                    return-void
                """))),
            clazz(t("Parser"), methods = listOf(method(t("Parser"), "unsafeParseFromJson", emptyList(), OBJECT, 4, static, """
                const-string v0, "$textKey"
                invoke-static { v0 }, Ltest/Reader;->text($STRING)$STRING
                move-result-object v2
                new-instance v0, $value
                const-string v3, "translated"
                invoke-direct { v0, v2, v3 }, $value-><init>($STRING$STRING)V
                return-object v0
            """))),
            clazz(t("Converter"), methods = listOf(conversion)),
            clazz(row, flags = rowTypeFlags, fields = listOf(field(row, "callback", FUNCTION)), methods = listOf(
                constructor(row, listOf(style, icon, label, FUNCTION), "iput-object p4, p0, $row->callback:$FUNCTION\nreturn-void", rowFlags))),
            clazz(style, flags = styleTypeFlags, fields = listOf(field(style, "color", "Ljava/lang/Integer;"))),
            clazz(normal, superclass = style, fields = listOf(field(normal, "INSTANCE", normal, static)), methods = listOf(
                method(normal, "<clinit>", emptyList(), "V", 2, static, """
                    const/4 v0, 0x0
                    new-instance v1, $normal
                    invoke-direct { v1 }, Ljava/lang/Object;-><init>()V
                    iput-object v0, v1, $style->color:Ljava/lang/Integer;
                    sput-object v1, $normal->INSTANCE:$normal
                    return-void
                """))),
            clazz(icon, methods = listOf(constructor(icon, listOf("I"), "return-void"))),
            clazz(label, flags = labelFlags, methods = listOf(constructor(label, listOf("I"), "return-void"))),
            clazz(controller, methods = listOf(
                method(controller, "select", listOf(STRING, STRING, "F", "Z"), "V", 8, public, """
                    const-string v0, "$COMMENT_SELECT"
                    const/4 v0, 0x0
                    invoke-static { v0, p1, p2 }, Ltest/Resolver;->find(Ltest/State;$STRING$STRING)$view
                    move-result-object v1
                    invoke-direct { p0 }, $controller->rows()Ljava/util/ArrayList;
                    return-void
                """),
                method(controller, "rows", emptyList(), "Ljava/util/ArrayList;", 7, AccessFlags.PRIVATE.value, """
                    const-string v0, "$COMMENT_ROWS"
                    sget-object v1, $normal->INSTANCE:$normal
                    new-instance v2, $icon
                    const v3, 0x7f080123
                    invoke-direct { v2, v3 }, $icon-><init>(I)V
                    new-instance v4, $label
                    const v3, 0x7f130456
                    invoke-direct { v4, v3 }, $label-><init>(I)V
                    const/4 v5, 0x0
                    new-instance v0, Ltest/ConcreteRow;
                    invoke-direct { v0, v1, v2, v4, v5 }, $row-><init>($style$icon$label$FUNCTION)V
                    return-object v0
                """))),
            clazz(renderer, fields = listOf(field(renderer, "popup", OBJECT)), methods = listOf(
                method(renderer, "show", listOf("Landroidx/fragment/app/Fragment;", view, "Ltest/State;", LIST, "F"), "V", 12, public, """
                    iget-object v0, p0, $renderer->popup:$OBJECT
                    ${if (guards) "if-nez v0, :done" else "nop"}
                    iget-object v0, p0, $renderer->popup:$OBJECT
                    ${if (guards) "if-nez v0, :done" else "nop"}
                    const/4 v2, 0x0
                    invoke-virtual { p1 }, Landroidx/fragment/app/Fragment;->requireContext()$context
                    move-result-object v3
                    invoke-static { p4 }, Ltest/Rows;->size(Ljava/lang/Iterable;)I
                    move-result v0
                    const/4 v1, 0x0
                    new-instance v0, $wrapper
                    invoke-direct { v0, v2, v1 }, $wrapper-><init>(${OBJECT}I)V
                    check-cast v2, $row
                    :done
                    return-void
                """))),
            clazz(wrapper, methods = listOf(constructor(wrapper, listOf(OBJECT, "I"), "return-void"),
                method(wrapper, "click", emptyList(), "V", 2, public, """
                    const/4 v0, 0x0
                    check-cast v0, $row
                    iget-object v0, v0, $row->callback:$FUNCTION
                    invoke-interface { v0 }, $FUNCTION->invoke()$OBJECT
                    return-void
                """), method(wrapper, "dismiss", emptyList(), "Z", 2, public, "const/4 v0, $dismiss\nreturn v0"))),
            clazz(t("CopyText"), methods = listOf(
                method(t("CopyText"), "toString", emptyList(), STRING, 2, public, "const-string v0, \"CopyText\"\nreturn-object v0"),
                method(t("CopyText"), "<init>", emptyList(), "V", 5, public, """
                    const/4 v0, 0x0
                    const v1, 0x7f080123
                    const v2, 0x7f130456
                    const/4 v3, 0x0
                    invoke-direct { p0, v0, v1, v2, v3 }, Ltest/CopyBase;-><init>(${OBJECT}IIZ)V
                    return-void
                """))),
            clazz("Ltest/CopyBase;", methods = listOf(constructor("Ltest/CopyBase;", listOf(OBJECT, "I", "I", "Z"), "return-void"))),
        )
    }

    private fun clazz(type: String, flags: Int = public, superclass: String = OBJECT,
                      interfaces: List<String> = emptyList(), fields: List<ImmutableField> = emptyList(),
                      methods: List<Method> = emptyList()) =
        ImmutableClassDef(type, flags, superclass, interfaces, null, null, fields, methods)
    private fun method(type: String, name: String, parameters: List<String>, result: String,
                       registers: Int, flags: Int, body: String): Method =
        MutableMethod(ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) },
            result, flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, body.trimIndent())
        }.let(ImmutableMethod::of)
}
