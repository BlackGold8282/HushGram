/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

@Suppress("unused")
val copyCommentPatch = bytecodePatch(
    name = "Copy comment",
    description = "Adds an optional Copy action to the common comment menu. Copies the original text with its line breaks.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("commentCopy")
        val menu = findCommentMenu()
        applyCommentMenu(menu)
        enableStatus("commentCopy")
    }
}

/** Validate the extension too, so a missing bridge can't leave the native renderer half changed. */
internal fun BytecodePatchContext.validateCommentStubs() {
    stub(COPY_HOOK.substringBefore("->"), "rows", listOf(LIST, OBJECT, "Landroid/content/Context;"), LIST)
    stub(COMMENT_NATIVE, "originalText", listOf(OBJECT), STRING)
    stub(COMMENT_NATIVE, "newRow", listOf(OBJECT), OBJECT)
    stub(COMMENT_NATIVE, "callback", listOf(OBJECT), OBJECT)
    stub(COPY_ROW, "<init>", List(4) { OBJECT }, "V", false)
}

private fun BytecodePatchContext.stub(type: String, name: String, parameters: List<String>, returns: String,
                                      static: Boolean = true) =
    mutableClassDefBy(type).methods.filter { it.name == name && it.parameters() == parameters && it.returnType == returns &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) == static }
        .singleOrNull() ?: refuse("extension has no unique $name bridge")

/** Only called after discovery and every accessibility/register/stub check succeeded. */
internal fun BytecodePatchContext.applyCommentMenu(menu: CommentMenu) {
    val row = stub(COPY_ROW, "<init>", List(4) { OBJECT }, "V", false)
    val text = stub(COMMENT_NATIVE, "originalText", listOf(OBJECT), STRING)
    val factory = stub(COMMENT_NATIVE, "newRow", listOf(OBJECT), OBJECT)
    val callback = stub(COMMENT_NATIVE, "callback", listOf(OBJECT), OBJECT)
    val renderer = mutableClassDefBy(menu.renderer.definingClass).methods.single { it.matches(menu.renderer) }
    val (rows, selected, context) = menu.spares
    renderer.addInstructionsAtControlFlowLabel(menu.at, """
        move-object/from16 v$rows, v${menu.rows}
        move-object/from16 v$selected, v${menu.selected}
        move-object/from16 v$context, v${menu.context}
        invoke-static { v$rows, v$selected, v$context }, $COPY_HOOK
        move-result-object v${menu.rows}
    """.trimIndent())
    replace(text, 2, """
        instance-of v0, p0, ${menu.text.definingClass}
        if-eqz v0, :unsupported
        check-cast p0, ${menu.text.definingClass}
        iget-object p0, p0, ${menu.text}
        return-object p0
        :unsupported
        const/4 v0, 0x0
        return-object v0
    """)
    mutableClassDefBy(COPY_ROW).setSuperClass(menu.rowConstructor.definingClass)
    val types = menu.rowConstructor.parameters()
    replace(row, 5, """
        check-cast p1, ${types[0]}
        check-cast p2, ${types[1]}
        check-cast p3, ${types[2]}
        check-cast p4, $FUNCTION
        invoke-direct { p0, p1, p2, p3, p4 }, ${menu.rowConstructor}
        return-void
    """)
    replace(factory, 6, """
        new-instance v0, $COPY_ROW
        sget-object v1, ${menu.style}
        new-instance v2, ${menu.iconConstructor.definingClass}
        const v4, ${menu.icon}
        invoke-direct { v2, v4 }, ${menu.iconConstructor}
        new-instance v3, ${menu.labelConstructor.definingClass}
        const v4, ${menu.label}
        invoke-direct { v3, v4 }, ${menu.labelConstructor}
        invoke-direct { v0, v1, v2, v3, p0 }, $COPY_ROW-><init>($OBJECT$OBJECT$OBJECT$OBJECT)V
        return-object v0
    """)
    replace(callback, 2, """
        instance-of v0, p0, $COPY_ROW
        if-eqz v0, :stock
        check-cast p0, ${menu.callback.definingClass}
        iget-object p0, p0, ${menu.callback}
        return-object p0
        :stock
        const/4 v0, 0x0
        return-object v0
    """)
}

private fun BytecodePatchContext.replace(method: MutableMethod, registers: Int, body: String) {
    val replacement = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
        ImmutableMethodImplementation(registers, emptyList(), null, null)).toMutable().apply {
        addInstructionsWithLabels(0, body.trimIndent())
    }
    val owner = mutableClassDefBy(method.definingClass)
    owner.methods.remove(method)
    owner.methods.add(replacement)
}
