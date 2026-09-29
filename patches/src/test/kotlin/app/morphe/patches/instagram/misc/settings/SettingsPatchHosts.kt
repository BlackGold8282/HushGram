/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settings

import app.morphe.patches.instagram.misc.extension.INSTAGRAM_APPLICATION
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

/**
 * Stand-ins for the Instagram classes the settings patch hooks, for a test that runs the whole
 * patch over a few classes instead of an APK: the application with its onCreate, and the main
 * activity with its onNewIntent.
 */
internal object SettingsPatchHosts {
    fun all(): List<ClassDef> {
        val returns = ImmutableInstruction10x(Opcode.RETURN_VOID)
        return listOf(
            ImmutableClassDef(
                INSTAGRAM_APPLICATION, AccessFlags.PUBLIC.value, "Landroid/app/Application;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        INSTAGRAM_APPLICATION, "onCreate", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                        ImmutableMethodImplementation(1, listOf(returns), null, null),
                    ),
                ),
            ),
            ImmutableClassDef(
                MAIN_ACTIVITY, AccessFlags.PUBLIC.value, "Landroid/app/Activity;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        MAIN_ACTIVITY, "onNewIntent", listOf(ImmutableMethodParameter("Landroid/content/Intent;", null, null)),
                        "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                        ImmutableMethodImplementation(2, listOf(returns), null, null),
                    ),
                ),
            ),
        )
    }
}
