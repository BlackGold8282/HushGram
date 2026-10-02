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
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

internal const val OVERRIDE_TITLE = "MetaConfig: Overrides"
internal const val OVERRIDE_BRIDGE = "$EXTENSION_PACKAGE/misc/DeveloperOptions;"
private const val MAIN = "Lcom/instagram/mainactivity/InstagramMainActivity;"
private const val MODAL = "Lcom/instagram/modal/ModalActivity;"
private const val IG_ACTIVITY = "Lcom/instagram/base/activity/IgFragmentActivity;"
private const val USER = "Lcom/instagram/common/session/UserSession;"
private const val ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"

internal data class OverrideEditor(val getter: String, val factory: String, val fragment: String, val present: String)

/** Resolve the editor's own native branch and session accessor, without shortened class names. */
internal fun BytecodePatchContext.findOverrideEditor(): OverrideEditor {
    val found = mutableListOf<Method>()
    val editors = mutableSetOf<String>()
    var hasBridge = false
    classDefForEach { clazz ->
        if (clazz.type == OVERRIDE_BRIDGE) hasBridge = true
        if (clazz.originalName() == "QuickExperimentEditFragment") editors += clazz.type
        if (!clazz.type.startsWith(EXTENSION_ROOT)) clazz.methods.filterTo(found) { method ->
            val strings = method.implementation?.instructions?.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
            }.orEmpty()
            strings.containsAll(listOf(OVERRIDE_TITLE, "TITLE_KEY", "IS_OVERRIDE_KEY"))
        }
    }
    if (!hasBridge) editorRefuse("extension has no override bridge class")
    val branch = found.singleOrNull() ?: editorRefuse("expected one native override branch, found ${found.size}")
    val code = branch.implementation!!.instructions.toList()
    val title = code.indices.singleOrNull {
        ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == OVERRIDE_TITLE
    } ?: editorRefuse("native override title is ambiguous")
    val newEditor = (maxOf(0, title - 9) until title).singleOrNull { index ->
        code[index].opcode == Opcode.NEW_INSTANCE &&
            ((code[index] as? ReferenceInstruction)?.reference as? TypeReference)?.type in editors
    } ?: editorRefuse("native override branch has no unique edit fragment")
    val fragment = ((code[newEditor] as ReferenceInstruction).reference as TypeReference).type
    val clazz = classDefBy(fragment)
    if (!AccessFlags.PUBLIC.isSet(clazz.accessFlags) || clazz.methods.count {
            it.name == "<init>" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != 1) editorRefuse("edit fragment has no public empty constructor")

    val session = classDefBy(USER).superclass ?: editorRefuse("user session has no base session")
    val mainGetter = classDefBy(MAIN).methods.filter {
        it.parameterTypes.isEmpty() && it.returnType == session && it.isPublicInstance()
    }.singleOrNull() ?: editorRefuse("main activity has no unique session accessor")
    val getter = classDefBy(IG_ACTIVITY).methods.singleOrNull {
        it.name == mainGetter.name && it.parameterTypes.isEmpty() && it.returnType == session && it.isPublicInstance()
    } ?: editorRefuse("native activity has no matching session accessor")
    if (getter.implementation?.instructions?.none {
            val ref = (it as? ReferenceInstruction)?.reference
            ref?.toString()?.startsWith("$MODAL->") == true
        } != false) editorRefuse("native session accessor doesn't support modal activities")

    val factory = (maxOf(0, newEditor - 8) until newEditor).mapNotNull { index ->
        if (code[index].opcode != Opcode.INVOKE_STATIC) null
        else ((code[index] as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
            it.parameterTypes.map(Any::toString) == listOf(ACTIVITY, session) && it.returnType.startsWith("L")
        }
    }.singleOrNull() ?: editorRefuse("override branch has no unique navigation factory")
    val present = (title + 1 until minOf(code.size, title + 10)).mapNotNull { index ->
        if (code[index].opcode != Opcode.INVOKE_STATIC) null
        else ((code[index] as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
            it.parameterTypes.map(Any::toString) == listOf(FRAGMENT, factory.returnType) && it.returnType == "V"
        }
    }.singleOrNull() ?: editorRefuse("override branch has no unique navigation presenter")
    for (reference in listOf(factory, present)) {
        val owner = classDefBy(reference.definingClass)
        if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || owner.methods.none {
                it.name == reference.name && it.parameterTypes == reference.parameterTypes && it.returnType == reference.returnType &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }) editorRefuse("native navigation reference isn't public static")
    }
    // Refuse a missing bridge before the Home handler can be changed.
    overrideStub()
    return OverrideEditor("$IG_ACTIVITY->${getter.name}()$session", factory.toString(), fragment, present.toString())
}

private fun Method.isPublicInstance() = AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags)

private fun BytecodePatchContext.overrideStub() = mutableClassDefBy(OVERRIDE_BRIDGE).methods.singleOrNull {
    it.name == "openOverridesNative" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
        it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags)
} ?: editorRefuse("extension has no unique override bridge")

/** Only a current logged-in Home/modal host can supply the native editor's session. */
internal fun BytecodePatchContext.fillOverrideEditor(editor: OverrideEditor) {
    val owner = mutableClassDefBy(OVERRIDE_BRIDGE)
    val old = overrideStub()
    val replacement = ImmutableMethod(
        old.definingClass, old.name, old.parameters, old.returnType, old.accessFlags, old.annotations,
        old.hiddenApiRestrictions, ImmutableMethodImplementation(6, emptyList(), null, null),
    ).toMutable().apply {
        addInstructionsWithLabels(0, """
            instance-of v0, p0, $MAIN
            if-nez v0, :session
            instance-of v0, p0, $MODAL
            if-eqz v0, :unavailable
            :session
            check-cast p0, $IG_ACTIVITY
            invoke-virtual { p0 }, ${editor.getter}
            move-result-object v0
            instance-of v1, v0, $USER
            if-eqz v1, :unavailable
            invoke-static { p0, v0 }, ${editor.factory}
            move-result-object v1
            new-instance v0, ${editor.fragment}
            invoke-direct { v0 }, ${editor.fragment}-><init>()V
            new-instance v2, Landroid/os/Bundle;
            invoke-direct { v2 }, Landroid/os/Bundle;-><init>()V
            const-string v3, "TITLE_KEY"
            const-string v4, "$OVERRIDE_TITLE"
            invoke-virtual { v2, v3, v4 }, Landroid/os/BaseBundle;->putString(Ljava/lang/String;Ljava/lang/String;)V
            const-string v3, "IS_OVERRIDE_KEY"
            const/4 v4, 0x1
            invoke-virtual { v2, v3, v4 }, Landroid/os/BaseBundle;->putBoolean(Ljava/lang/String;Z)V
            invoke-virtual { v0, v2 }, Landroidx/fragment/app/Fragment;->setArguments(Landroid/os/Bundle;)V
            invoke-static { v0, v1 }, ${editor.present}
            const/4 v0, 0x1
            return v0
            :unavailable
            const/4 v0, 0x0
            return v0
        """.trimIndent())
    }
    owner.methods.remove(old)
    owner.methods.add(replacement)
}

private fun editorRefuse(detail: String): Nothing = throw PatchException("Open developer options: $detail")
