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
            // The selected comment's register is counted back from the list, so a wide value between them moves it.
            "wide renderer parameter" to nativeClasses("First", wideState = true),
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

    @Test fun aNearbyOriginalKeyCannotAuthorizeAReadOfTranslation() {
        refusesFlow(nativeClasses("NearbyKey", parserFlow = "nearby-translation"))
    }

    @Test fun nativeEqualsDiscriminatorAndReturnedObjectAliasesStaySupported() {
        for (flow in listOf("return-alias", "return-joined", "early-null")) {
            val patch = PatchContexts.of(nativeClasses("NativeParser", parserFlow = "equals", converterFlow = flow) + extension())
            val menu = patch.findCommentMenu()
            assertEquals("originalNativeParser", menu.text.name)
            patch.applyCommentMenu(menu)
            assertWiring(patch, menu)
        }
    }

    @Test fun aDiscriminatorCannotCompareTheTranslationKeyInstead() {
        refusesFlow(nativeClasses("WrongKey", parserFlow = "equals-translation"))
    }

    @Test fun comparingAnotherValueToTextCannotEstablishTheCurrentFieldName() {
        refusesFlow(nativeClasses("ValueCompared", parserFlow = "equals-value"))
    }

    @Test fun nativeReaderBoundariesMustReturnTheirReadFromTheSameReceiverAndAdvanceExactlyOnce() {
        for (flow in listOf("helper-wrong-receiver", "helper-overwrite", "helper-no-advance",
                "helper-extra-advance", "helper-advance-before-name", "helper-bypass", "advance-after-key")) {
            refusesFlow(nativeClasses("ReaderBoundary", parserFlow = flow))
        }
    }

    @Test fun aBranchCannotBypassTheOriginalKeyDiscriminator() {
        refusesFlow(nativeClasses("KeyBypass", parserFlow = "equals-bypass"))
    }

    @Test fun anOverwrittenEqualityResultCannotAuthorizeOriginalText() {
        refusesFlow(nativeClasses("KeyResult", parserFlow = "equals-result"))
    }

    @Test fun anOriginalObjectCannotBeDiscardedForATranslatedObject() {
        refusesFlow(nativeClasses("Discarded", converterFlow = "discard"))
    }

    @Test fun everyReturnedObjectMustCarryTheVerifiedOriginalText() {
        refusesFlow(nativeClasses("OtherReturn", converterFlow = "other-return"))
    }

    @Test fun aReturnedObjectCannotBypassInitialization() {
        refusesFlow(nativeClasses("Uninitialized", converterFlow = "uninitialized"))
    }

    @Test fun anOverwriteCannotReplaceTheVerifiedReturnedObject() {
        refusesFlow(nativeClasses("ObjectOverwrite", converterFlow = "return-overwrite"))
    }

    @Test fun aConstructorExceptionCannotReturnAnUninitializedObject() {
        refusesFlow(nativeClasses("InitHandler", converterFlow = "return-handler"))
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
                FixtureDex.classesHolding(bundle, JSON_ROOT_FIELD) +
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
                              getterFlow: String = "alias", styleTypeFlags: Int = public or AccessFlags.ABSTRACT.value,
                              parserFlow: String = "direct", wideState: Boolean = false): List<ClassDef> {
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
        val json = t("Json")
        val reader = t("Reader")
        val token = t("Token")
        val expected = t("Expected")
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
            "const-string v2, \"translated\"" else if (converterFlow == "early-null") "if-eqz p0, :unsupported" else ""
        val afterRead = when (converterFlow) {
            "branch" -> ":build"
            "handler" -> "goto :build\nmove-exception v1\n:build"
            "alias" -> "move-object v1, v2\nconst/4 v2, 0x0"
            else -> ""
        }
        val returnFlow = when (converterFlow) {
            "discard" -> "new-instance v1, $view\nconst-string v2, \"translated\"\ninvoke-direct { v1, p0, v2, v3 }, $view-><init>($raw$STRING$STRING)V\nreturn-object v1"
            "discard-raw" -> "new-instance v1, $view\nconst/4 v0, 0x0\nconst-string v2, \"translated\"\ninvoke-direct { v1, v0, v2, v3 }, $view-><init>($raw$STRING$STRING)V\nreturn-object v1"
            "other-return" -> "if-eqz p0, :original\nnew-instance v1, $view\nconst-string v2, \"translated\"\ninvoke-direct { v1, p0, v2, v3 }, $view-><init>($raw$STRING$STRING)V\nreturn-object v1\n:original\nreturn-object v0"
            "uninitialized" -> "new-instance v1, $view\nreturn-object v1"
            "return-overwrite" -> "const/4 v0, 0x0\nreturn-object v0"
            "return-alias" -> "move-object v1, v0\nconst/4 v0, 0x0\ncheck-cast v1, $view\nreturn-object v1"
            "return-joined" -> "if-eqz p0, :other\nmove-object v1, v0\ngoto :done\n:other\nmove-object v1, v0\n:done\ncheck-cast v1, $view\nreturn-object v1"
            "early-null" -> "return-object v0\n:unsupported\nconst/4 v0, 0x0\nreturn-object v0"
            "return-handler" -> "return-object v0\nmove-exception v1\nreturn-object v0"
            else -> "return-object v0"
        }
        var conversion = method(t("Converter"), "convert", listOf(raw), view, 5, static, """
            $beforeRead
            invoke-interface { p0 }, $raw->original()$STRING
            move-result-object v2
            $afterRead
            new-instance v0, $view
            const-string v3, "translated"
            invoke-direct { v0, p0, ${if (converterFlow == "alias") "v1" else "v2"}, v3 }, $view-><init>($raw$STRING$STRING)V
            $returnFlow
        """)
        if (converterFlow in setOf("handler", "return-handler")) {
            val code = conversion.code()
            val read = code.indexOfFirst { it.call()?.let { call ->
                if (converterFlow == "handler") call.definingClass == raw else call.definingClass == view && call.name == "<init>"
            } == true }
            val handler = code.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
            fun address(at: Int) = code.take(at).sumOf { it.codeUnits }
            conversion = ImmutableMethod(conversion.definingClass, conversion.name, conversion.parameters,
                conversion.returnType, conversion.accessFlags, null, null,
                ImmutableMethodImplementation(5, code, listOf(ImmutableTryBlock(address(read), code[read].codeUnits,
                    listOf(ImmutableExceptionHandler("Ljava/lang/Exception;", address(handler))))), null))
        }
        val parserRead = """
                const-string v0, "$textKey"
                ${if (parserFlow == "equals-translation") "const-string v0, \"text_translation\"" else ""}
                ${if (parserFlow == "equals-bypass") "if-eqz p0, :read" else ""}
                invoke-static { p0 }, $reader->${if (parserFlow == "equals-value") "text" else "key"}($json)$STRING
                move-result-object v1
                ${if (parserFlow == "nearby-translation") "const-string v3, \"text_translation\"" else ""}
                ${if (parserFlow == "advance-after-key") "invoke-virtual { p0 }, $json->next()$token" else ""}
                invoke-virtual { v1, ${if (parserFlow == "nearby-translation") "v3" else "v0"} }, $STRING->equals($OBJECT)Z
                move-result v1
                ${if (parserFlow == "equals-result") "const/4 v1, 0x1" else ""}
                if-eqz v1, :empty
                :read
                invoke-static { p0 }, $reader->text($json)$STRING
            """.trimIndent()
        val keyHelper = """
            ${if (parserFlow == "helper-bypass") "if-eqz p0, :done" else ""}
            ${if (parserFlow == "helper-wrong-receiver") "const/4 v1, 0x0" else ""}
            ${if (parserFlow == "helper-advance-before-name") "invoke-virtual { p0 }, $json->next()$token" else ""}
            invoke-virtual { ${if (parserFlow == "helper-wrong-receiver") "v1" else "p0"} }, $json->name()$STRING
            move-result-object v0
            if-eqz v0, :bad
            ${if (parserFlow == "helper-no-advance") "" else "invoke-virtual { p0 }, $json->next()$token"}
            ${if (parserFlow == "helper-extra-advance") "invoke-virtual { p0 }, $json->next()$token" else ""}
            ${if (parserFlow == "helper-overwrite") "const-string v0, \"text\"" else ""}
            :done
            return-object v0
            :bad
            const/4 v0, 0x0
            throw v0
        """
        return listOf(
            clazz(token, flags = public or AccessFlags.ENUM.value or AccessFlags.FINAL.value, superclass = "Ljava/lang/Enum;",
                fields = listOf(field(token, "FIELD", token, static), field(token, "STRING", token, static)), methods = listOf(
                    constructor(token, listOf(STRING, "I"), "invoke-direct { p0, p1, p2 }, Ljava/lang/Enum;-><init>(${STRING}I)V\nreturn-void"),
                    method(token, "<clinit>", emptyList(), "V", 3, static, """
                        const-string v1, "FIELD_NAME"
                        const/4 v2, 0x0
                        new-instance v0, $token
                        invoke-direct { v0, v1, v2 }, $token-><init>(${STRING}I)V
                        sput-object v0, $token->FIELD:$token
                        const-string v1, "VALUE_STRING"
                        const/4 v2, 0x1
                        new-instance v0, $token
                        invoke-direct { v0, v1, v2 }, $token-><init>(${STRING}I)V
                        sput-object v0, $token->STRING:$token
                        return-void
                    """))),
            clazz(json, flags = public or AccessFlags.ABSTRACT.value,
                fields = listOf(field(json, "token", token), field(json, "name", STRING), field(json, "value", STRING)),
                methods = listOf(
                    method(json, "name", emptyList(), STRING, 2, public, "iget-object v0, p0, $json->name:$STRING\nreturn-object v0"),
                    method(json, "next", emptyList(), token, 2, public, "sget-object v0, $token->STRING:$token\niput-object v0, p0, $json->token:$token\nreturn-object v0"),
                    ImmutableMethod(json, "current", emptyList(), token, public or AccessFlags.ABSTRACT.value, null, null, null),
                    method(json, "value", emptyList(), STRING, 3, public, """
                        iget-object v0, p0, $json->token:$token
                        sget-object v1, $token->STRING:$token
                        if-eq v0, v1, :value
                        sget-object v1, $token->FIELD:$token
                        if-eq v0, v1, :name
                        const/4 v0, 0x0
                        return-object v0
                        :name
                        iget-object v0, p0, $json->name:$STRING
                        return-object v0
                        :value
                        iget-object v0, p0, $json->value:$STRING
                        return-object v0
                    """))),
            clazz(expected, fields = listOf(field(expected, "name", STRING))),
            clazz(t("Root"), methods = listOf(method(t("Root"), "unwrap", listOf(json, expected), OBJECT, 7, static, """
                iget-object v4, p1, $expected->name:$STRING
                invoke-virtual { p0 }, $json->next()$token
                move-result-object v0
                sget-object v1, $token->FIELD:$token
                if-eq v0, v1, :name
                const-string v0, "$JSON_ROOT_FIELD"
                const/4 v0, 0x0
                throw v0
                :name
                invoke-virtual { p0 }, $json->name()$STRING
                move-result-object v3
                invoke-virtual { v4, v3 }, $STRING->equals($OBJECT)Z
                move-result v0
                if-nez v0, :matched
                const-string v0, "$JSON_ROOT_MISMATCH"
                const/4 v0, 0x0
                throw v0
                :matched
                invoke-virtual { p0 }, $json->next()$token
                return-object p0
            """))),
            // A bare Reader(String) call has no implementation proving a native JSON boundary.
            clazz(reader, methods = listOf(method(reader, "key", listOf(json), STRING, 3, static, keyHelper),
                method(reader, "text", listOf(json), STRING, 2, static,
                    "invoke-virtual { p0 }, $json->value()$STRING\nmove-result-object v0\nreturn-object v0"))),
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
            clazz(t("Parser"), methods = listOf(method(t("Parser"), "unsafeParseFromJson", listOf(json), OBJECT, 5, static, """
                $parserRead
                move-result-object v2
                new-instance v0, $value
                const-string v3, "translated"
                invoke-direct { v0, v2, v3 }, $value-><init>($STRING$STRING)V
                return-object v0
                :empty
                const/4 v0, 0x0
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
                method(renderer, "show", listOf("Landroidx/fragment/app/Fragment;", view, if (wideState) "J" else "Ltest/State;", LIST, "F"), "V", if (wideState) 13 else 12, public, """
                    iget-object v0, p0, $renderer->popup:$OBJECT
                    ${if (guards) "if-nez v0, :done" else "nop"}
                    iget-object v0, p0, $renderer->popup:$OBJECT
                    ${if (guards) "if-nez v0, :done" else "nop"}
                    const/4 v2, 0x0
                    invoke-virtual { p1 }, Landroidx/fragment/app/Fragment;->requireContext()$context
                    move-result-object v3
                    invoke-static { ${if (wideState) "p5" else "p4"} }, Ltest/Rows;->size(Ljava/lang/Iterable;)I
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
