/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class PureBlackTest {
    private val palette = "Lfixture/Palette;"
    private val extension = "Lapp/hushgram/extension/instagram/settings/Colors;"

    /** Prism's black and Meta AI's night background go pure black; another color, or either at another value, stays. */
    @Test
    fun theColorTableGetsPureBlack() {
        val colors = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            """
                <resources>
                    <color name="igds_prism_black">#ff0c1014</color>
                    <color name="meta_ai_fullscreen_primary_background">#FF0C1014</color>
                    <color name="igds_prism_gray_1500">#ff191c1f</color>
                    <color name="sticker_tray_refresh_text">#ff0c1014</color>
                </resources>
            """.trimIndent().byteInputStream(),
        )

        assertEquals(PRISM_BLACK_COLORS, blackenColors(colors))

        val values = colors.getElementsByTagName("color").let { list ->
            (0 until list.length).associate { (list.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent } }
        }
        assertEquals("#ff000000", values["igds_prism_black"])
        assertEquals("#ff000000", values["meta_ai_fullscreen_primary_background"])
        assertEquals("#ff191c1f", values["igds_prism_gray_1500"])
        assertEquals("a sticker's text isn't the theme", "#ff0c1014", values["sticker_tray_refresh_text"])
        assertEquals("a second pass finds nothing", emptyList<String>(), blackenColors(colors))
    }

    /** Both literal forms go pure black on their own registers; other colors and the extension's own stay. */
    @Test
    fun theLiteralsGetPureBlack() {
        val patch = PatchContexts.of(listOf(
            classDef(palette, """
                const v0, 0xff0c1014
                const-wide v2, 0xff0c1014L
                const v4, 0xff25292e
                const-wide v2, 0xff25292eL
                return-void
            """),
            classDef(extension, """
                const v0, 0xff0c1014
                return-void
            """),
        ))

        assertEquals(listOf("$palette->colors"), patch.blackenLiterals())

        val code = patch.code(palette)
        assertEquals(Opcode.CONST, code[0].opcode)
        assertEquals(0, (code[0] as OneRegisterInstruction).registerA)
        assertEquals(PURE_BLACK.toInt(), (code[0] as WideLiteralInstruction).wideLiteral.toInt())
        assertEquals(Opcode.CONST_WIDE, code[1].opcode)
        assertEquals(2, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(PURE_BLACK, (code[1] as WideLiteralInstruction).wideLiteral)
        assertEquals(0xff25292e.toInt(), (code[2] as WideLiteralInstruction).wideLiteral.toInt())
        assertEquals(0xff25292eL, (code[3] as WideLiteralInstruction).wideLiteral)
        assertTrue("the extension's own color", patch.code(extension).any(::isPrismBlack))
    }

    /** In each declared build, no Instagram method loads Prism's black any more, and the Compose palette was among them. */
    @Test
    fun eachDeclaredBuildLosesPrismBlack() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.implementation?.instructions?.any(::isPrismBlack) == true }) {
                            classes += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val patch = PatchContexts.of(classes)

                val changed = patch.blackenLiterals()

                assertTrue("${bundle.name}: the Compose palette", changed.any { it.startsWith("$COMPOSE_PALETTE->") })
                for (classDef in classes) {
                    assertTrue("${bundle.name}: ${classDef.type}", patch.classDefBy(classDef.type).methods.none { method ->
                        method.implementation?.instructions?.any(::isPrismBlack) == true
                    })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.code(type: String): List<Instruction> =
        classDefBy(type).methods.single().implementation!!.instructions.toList()

    private fun classDef(type: String, body: String): ClassDef {
        val mutable = MutableMethod(
            ImmutableMethod(
                type, "colors", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(6, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        val method: Method = ImmutableMethod.of(mutable)
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(method))
    }
}
