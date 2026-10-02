/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.autoscroll

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterEveryBooleanReturn
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Keep Reels auto scroll on"

internal const val REEL_AUTO_SCROLL = "$EXTENSION_PACKAGE/reels/ReelAutoScroll;"
internal const val AUTO_SCROLL_ANSWER = "$REEL_AUTO_SCROLL->answer(I)Z"
internal const val AUTO_SCROLL_SAVED = "$REEL_AUTO_SCROLL->saved(I)Z"
internal const val AUTO_SCROLL_CHOSEN = "$REEL_AUTO_SCROLL->chosen(I)V"

/**
 * The markers, after Instagram's release prefix, of the Reels auto scroll plugin's check of whether
 * auto scroll is on and of its handler for the switches that turn it on or off.
 */
internal const val IS_AUTOSCROLL_ACTIVE = "ClipsOptInAutoscrollPluginImpl_isDurationAutoscrollActive"
internal const val AUTOSCROLL_MODE_CLICK = "ClipsOptInAutoscrollPluginImpl_handleAutoscrollModeClick"

/** The saved auto scroll preference's key, and Kotlin's name for its getter. Its class's initializer loads both. */
internal const val AUTOSCROLL_PREFERENCE = "preference_clips_auto_scroll_enabled"
internal const val AUTOSCROLL_GETTER_NAME = "getClipsAutoscrollEnabled(Lcom/instagram/preferences/user/UserPreferences;)Z"

/** The server setting's value under which the Reels viewer's onPause clears the saved preference. */
internal const val AUTO_SCROLL_SURFACE = "auto_scroll"

/** What the Reels tab's long-press action keeps, to reach the signed-in account. */
internal const val MAIN_ACTIVITY = "Lcom/instagram/mainactivity/InstagramMainActivity;"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * Auto scroll in Reels stays the way it was last set. See the extension's ReelAutoScroll for the
 * rule. Off in the default selection: auto scroll is Instagram's own choice to make, and keeping it
 * on across restarts is the user's pick. Asked for in #21.
 *
 * Instagram 449's auto scroll plugin answers whether auto scroll is on from memory, a timer or a
 * saved preference, as its server says, and every reader (the scroller and each auto scroll switch)
 * asks the plugin. Each of its answers passes through the extension, and so does each answer of the
 * saved preference's getter, through a hook of its own that remembers nothing. The plugin's handler
 * for its switches hands the extension the choice first thing, and so does the Reels tab's
 * long-press action, right after it saves its choice.
 *
 * Every method is found by Instagram's own markers, strings and kept class names, and everything is
 * found and checked before anything changes, so a build that differs stops the patch naming what
 * it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val keepReelsAutoScrollPatch = bytecodePatch(
    name = "Keep Reels auto scroll on",
    description = "Once you turn on Instagram's auto scroll in Reels, it stays on after Instagram restarts or you " +
        "leave Reels, until you turn it off yourself.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelAutoScroll")
        val sites = findReelAutoScroll()
        keepReelAutoScroll(sites)
        enableStatus("reelAutoScroll")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** A method by its class, name and parameters, which is how it's found again to change it. */
internal class MethodSite(val definingClass: String, val name: String, val parameters: List<String>) {
    override fun toString() = "$definingClass->$name(${parameters.joinToString("")})"
}

/** Every place the patch changes. */
internal class ReelAutoScrollSites(
    /** The plugin's check of whether auto scroll is on: every return is answered. */
    val isActive: MethodSite,
    /** The saved preference's getter: every return goes through [AUTO_SCROLL_SAVED]. */
    val getter: MethodSite,
    /** The plugin's handler for its switches, and the register of its choice, read first thing. */
    val click: MethodSite,
    val clickChoice: Int,
    /** The Reels tab's long-press action, the index right after it saves its choice, and the choice's register. */
    val toggle: MethodSite,
    val toggleAt: Int,
    val toggleChoice: Int,
)

/**
 * Finds the plugin's two methods by their markers, both in one class: the check, an instance
 * method taking the [USER_SESSION] and answering a boolean, and the handler, an instance method
 * taking the choice last as a boolean. The saved preference's class is the one whose initializer
 * loads [AUTOSCROLL_PREFERENCE] and [AUTOSCROLL_GETTER_NAME]; its one static getter answering a
 * boolean has to be read by the check, and its one static setter, taking what the getter takes and
 * a boolean, has to be called by the handler.
 *
 * The setter has three callers on 449: the handler, the Reels viewer's onPause, which loads
 * [AUTO_SCROLL_SURFACE] and clears the preference, and the Reels tab's long-press action, an
 * instance method taking nothing in a class keeping a [MAIN_ACTIVITY]. That last one, which calls
 * the setter once with no jump landing right after the call, is where its choice is read.
 *
 * Fails when any of them isn't there, or there's more than one, since that's an update this patch
 * hasn't seen.
 */
internal fun BytecodePatchContext.findReelAutoScroll(): ReelAutoScrollSites {
    val marked = mutableMapOf<String, MutableList<Method>>()
    val preferenceClasses = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            method.markers().filter { it == IS_AUTOSCROLL_ACTIVE || it == AUTOSCROLL_MODE_CLICK }.distinct()
                .forEach { marked.getOrPut(it) { mutableListOf() } += method }
        }
        if (classDef.methods.any { it.name == "<clinit>" && it.holdsString(AUTOSCROLL_PREFERENCE) }) preferenceClasses += classDef
    }
    fun single(marker: String): Method {
        val found = marked[marker].orEmpty()
        return found.singleOrNull() ?: refuse("expected one method marked $marker, found ${found.size}")
    }

    val isActive = single(IS_AUTOSCROLL_ACTIVE)
    if (isActive.isStatic() || isActive.returnType != "Z" || isActive.parameters() != listOf(USER_SESSION)) {
        refuse("${isActive.text()}, marked $IS_AUTOSCROLL_ACTIVE, isn't an instance ($USER_SESSION) method answering a boolean")
    }
    val click = single(AUTOSCROLL_MODE_CLICK)
    if (click.definingClass != isActive.definingClass) refuse("$AUTOSCROLL_MODE_CLICK isn't in ${isActive.definingClass}")
    if (click.isStatic() || click.returnType != "V" || click.parameters().lastOrNull() != "Z") {
        refuse("${click.text()}, marked $AUTOSCROLL_MODE_CLICK, isn't an instance method taking the choice last and returning nothing")
    }
    if (0 in click.jumpTargets()) refuse("something in ${click.text()} jumps back to its first instruction")

    val preference = preferenceClasses.singleOrNull()
        ?: refuse("expected one class whose initializer loads \"$AUTOSCROLL_PREFERENCE\", found ${preferenceClasses.size}")
    if (preference.methods.none { it.name == "<clinit>" && it.holdsString(AUTOSCROLL_GETTER_NAME) }) {
        refuse("${preference.type} doesn't name \"$AUTOSCROLL_GETTER_NAME\"")
    }
    val getters = preference.methods.filter {
        it.isStatic() && it.returnType == "Z" && it.parameters().size == 1 && it.parameters().single().startsWith("L")
    }
    val getter = getters.singleOrNull()
        ?: refuse("expected ${preference.type} to have one static getter answering a boolean, found ${getters.size}")
    val setters = preference.methods.filter {
        it.isStatic() && it.returnType == "V" && it.parameters() == listOf(getter.parameters().single(), "Z")
    }
    val setter = setters.singleOrNull()
        ?: refuse("expected ${preference.type} to have one static setter taking a boolean, found ${setters.size}")
    if (isActive.code().none { it.calls(getter) }) refuse("${isActive.text()} doesn't read ${getter.text()}")
    if (click.code().none { it.calls(setter) }) refuse("${click.text()} doesn't save to ${setter.text()}")
    if (isActive.code().none { it.opcode == Opcode.RETURN } || getter.code().none { it.opcode == Opcode.RETURN }) {
        refuse("${isActive.text()} or ${getter.text()} has no return to answer at")
    }

    val callers = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.filter { method -> method.code().any { it.calls(setter) } }.forEach { callers += classDef to it }
    }
    val others = callers.filter { (_, method) -> method.text() != click.text() }
    val pauses = others.filter { (_, method) ->
        method.name == "onPause" && !method.isStatic() && method.parameters().isEmpty() && method.returnType == "V" &&
            method.holdsString(AUTO_SCROLL_SURFACE)
    }
    val toggles = others - pauses.toSet()
    if (pauses.size != 1 || toggles.size != 1) {
        refuse(
            "expected ${setter.text()} to be called by ${click.text()}, one onPause loading \"$AUTO_SCROLL_SURFACE\" " +
                "and one more method, found ${others.map { it.second.text() }}",
        )
    }
    val (toggleClass, toggle) = toggles.single()
    if (toggle.isStatic() || toggle.returnType != "V" || toggle.parameters().isNotEmpty()) {
        refuse("${toggle.text()}, which saves to ${setter.text()}, isn't an instance method taking and returning nothing")
    }
    if (toggleClass.fields.none { it.type == MAIN_ACTIVITY && !AccessFlags.STATIC.isSet(it.accessFlags) }) {
        refuse("${toggleClass.type}, which saves to ${setter.text()}, keeps no $MAIN_ACTIVITY")
    }
    val code = toggle.code()
    val saves = code.indices.filter { code[it].calls(setter) }
    val save = saves.singleOrNull() ?: refuse("${toggle.text()} calls ${setter.text()} ${saves.size} times, expected once")
    val toggleChoice = code[save].argumentRegisters().getOrNull(1)
        ?: refuse("${toggle.text()}'s call to ${setter.text()} names no choice")
    val toggleAt = save + 1
    if (toggleAt >= code.size || toggleAt in toggle.jumpTargets()) {
        refuse("${toggle.text()} has no place right after its call to ${setter.text()} that only that call leads to")
    }

    return ReelAutoScrollSites(
        isActive.site(), getter.site(), click.site(), click.parameterRegisterNumber(click.parameterTypes.lastIndex),
        toggle.site(), toggleAt, toggleChoice,
    )
}

/**
 * The handler's choice goes to [AUTO_SCROLL_CHOSEN] first thing, and the long-press action's right
 * after it saves it. Then every return of the check passes its answer through [AUTO_SCROLL_ANSWER],
 * and every return of the getter through [AUTO_SCROLL_SAVED], at the return's own label, so a branch
 * straight to a return passes through it too.
 */
internal fun BytecodePatchContext.keepReelAutoScroll(sites: ReelAutoScrollSites) {
    mutable(sites.click).addInstructions(
        0,
        "invoke-static/range { v${sites.clickChoice} .. v${sites.clickChoice} }, $AUTO_SCROLL_CHOSEN",
    )
    mutable(sites.toggle).addInstructions(
        sites.toggleAt,
        "invoke-static/range { v${sites.toggleChoice} .. v${sites.toggleChoice} }, $AUTO_SCROLL_CHOSEN",
    )
    mutable(sites.isActive).filterEveryBooleanReturn(PATCH, AUTO_SCROLL_ANSWER)
    mutable(sites.getter).filterEveryBooleanReturn(PATCH, AUTO_SCROLL_SAVED)
}

private fun BytecodePatchContext.mutable(site: MethodSite): MutableMethod =
    mutableClassDefBy(site.definingClass).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }

private fun Method.site() = MethodSite(definingClass, name, parameters())

private fun Method.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

private fun Method.text() = "$definingClass->$name(${parameters().joinToString("")})$returnType"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.holdsString(value: String) = code().any {
    (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((it as ReferenceInstruction).reference as StringReference).string == value
}

/** Whether this instruction calls [method]. */
private fun Instruction.calls(method: Method): Boolean {
    val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return called.definingClass == method.definingClass && called.name == method.name &&
        called.returnType == method.returnType && called.parameterTypes.map(CharSequence::toString) == method.parameters()
}

/** The registers an invoke hands over, in order. */
private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
