/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction51l
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val PATCH = "Pure black dark mode"

/**
 * Instagram's black on its Prism redesign: the dark theme's background and status bar, and the light
 * theme's text. Menus, sheets and buttons in the dark theme use lighter grays of their own.
 */
internal const val PRISM_BLACK = 0xff0c1014L
internal const val PURE_BLACK = 0xff000000L

/** The color resources set to [PRISM_BLACK]: Prism's black, and Meta AI's full-screen background at night. */
internal val PRISM_BLACK_COLORS = listOf("igds_prism_black", "meta_ai_fullscreen_primary_background")

/** The Compose palette Instagram's newer screens draw from, a kept name. */
internal const val COMPOSE_PALETTE = "Lcom/instagram/compose/core/theme/BasePrismColors;"

/**
 * Sets each of [PRISM_BLACK_COLORS] in one decoded colors.xml that holds [PRISM_BLACK] to
 * [PURE_BLACK], and answers which ones it set.
 */
internal fun blackenColors(colors: Document): List<String> {
    val list = colors.getElementsByTagName("color")
    val set = mutableListOf<String>()
    for (element in (0 until list.length).map { list.item(it) as Element }) {
        val name = element.getAttribute("name")
        if (name !in PRISM_BLACK_COLORS) continue
        if (element.textContent.trim().lowercase() != "#%08x".format(PRISM_BLACK)) continue
        element.textContent = "#%08x".format(PURE_BLACK)
        set += name
    }
    return set
}

/**
 * Whether [instruction] loads [PRISM_BLACK]: as an int (a View color) or as a long (a Compose
 * Color's argument). Neither fits a shorter form of either opcode.
 */
internal fun isPrismBlack(instruction: Instruction): Boolean = when (instruction.opcode) {
    Opcode.CONST -> (instruction as WideLiteralInstruction).wideLiteral.toInt() == PRISM_BLACK.toInt()
    Opcode.CONST_WIDE -> (instruction as WideLiteralInstruction).wideLiteral == PRISM_BLACK
    else -> false
}

/**
 * Loads [PURE_BLACK] wherever Instagram's own code loads [PRISM_BLACK], with the same opcode into
 * the same register, and answers the methods it changed. The extension's own colors are left alone.
 */
internal fun BytecodePatchContext.blackenLiterals(): List<String> {
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.implementation?.instructions?.any(::isPrismBlack) == true) found += classDef.type to method
        }
    }
    val changed = mutableListOf<String>()
    found.groupBy({ it.first }, { it.second }).forEach { (type, methods) ->
        val mutable = mutableClassDefBy(type)
        for (method in methods) {
            val target = mutable.methods.single {
                it.name == method.name && it.returnType == method.returnType &&
                    it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
            }
            target.implementation!!.instructions.toList().forEachIndexed { index, instruction ->
                if (!isPrismBlack(instruction)) return@forEachIndexed
                val register = (instruction as OneRegisterInstruction).registerA
                target.replaceInstruction(
                    index,
                    if (instruction.opcode == Opcode.CONST) BuilderInstruction31i(Opcode.CONST, register, PURE_BLACK.toInt())
                    else BuilderInstruction51l(Opcode.CONST_WIDE, register, PURE_BLACK),
                )
            }
            changed += "$type->${method.name}"
        }
    }
    return changed
}

/**
 * A resource patch, for the color table the View screens and the status bar read: the dark themes
 * point their background at [PRISM_BLACK_COLORS]' first. Manager decodes Instagram's resources for
 * Remove the advertising ID already, so this costs no second decode when both are picked.
 */
private val pureBlackColorsPatch = resourcePatch {
    execute {
        val values = get("res").listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
        val set = values.filter { java.io.File(it, "colors.xml").isFile }.flatMap { directory ->
            document("res/${directory.name}/colors.xml").use(::blackenColors)
        }
        if (PRISM_BLACK_COLORS.first() !in set) {
            throw PatchException("$PATCH: no colors.xml sets ${PRISM_BLACK_COLORS.first()} to #%08x".format(PRISM_BLACK))
        }
    }
}

/**
 * Gives Instagram's dark mode a pure black background. A patch-time choice with no switch: the
 * color table can't follow one, so it's listed under Set when you patched.
 */
@Suppress("unused")
val pureBlackPatch = bytecodePatch(
    name = "Pure black dark mode",
    description = "Instagram's dark mode uses pure black instead of its near-black gray, which looks deeper and saves " +
        "power on an OLED screen. Menus, sheets and buttons keep their own grays so they stay easy to see. " +
        "Chosen when you patch, with no switch.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch, pureBlackColorsPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("pureBlack")
        val changed = blackenLiterals()
        if (changed.none { it.startsWith("$COMPOSE_PALETTE->") }) {
            throw PatchException("$PATCH: $COMPOSE_PALETTE no longer loads #%08x".format(PRISM_BLACK))
        }
        enableStatus("pureBlack")
    }
}
