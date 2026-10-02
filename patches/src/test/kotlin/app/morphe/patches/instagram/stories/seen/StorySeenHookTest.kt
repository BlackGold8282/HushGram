/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorySeenHookTest {
    /** The hooks the patch writes and the stubs it fills are in the extension the bundle ships, static. */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        fun declared(type: String, public: Boolean) = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && (!public || AccessFlags.PUBLIC.isSet(it.accessFlags)) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue(TO_SEND.substringAfter("->") in declared(STORY_SEEN, public = true))
        assertTrue(BIND_BUTTON.substringAfter("->") in declared(STORY_SEEN_BUTTON, public = true))
        val stubs = declared(STORY_SEEN, public = false) + declared(STORY_SEEN_BUTTON, public = false)
        for (stub in listOf(
            "emptyBatch()Ljava/lang/Object;", "seenStories(Ljava/lang/Object;)Ljava/util/Map;",
            "send(Ljava/lang/Object;Ljava/lang/Object;)V", "storyId(Ljava/lang/Object;)Ljava/lang/String;",
            "itemView(Ljava/lang/Object;)Landroid/view/View;",
        )) {
            assertTrue("$stub is not in the extension: $stubs", stub in stubs)
        }
    }

    /**
     * The send asks first and goes on with what it's told, in the batch's own register, or returns.
     * The store's other methods taking a batch, and the rebuild for a retry, stay as they are.
     */
    @Test
    fun theSendAsksBeforeItBuildsTheRequest() {
        val context = PatchContexts.of(standIns())

        context.holdBackStoryViews()

        val patched = context.mutableClassDefBy(STORE)
        assertSendHooked("the send", patched.methods.single { it.name == "A0O" }, BATCH)
        for (name in listOf("A0P", "A0Q", "A0J", "A0L", "A00")) {
            assertTrue("$name was touched", patched.methods.single { it.name == name }.code().none { it.referenceText() == TO_SEND })
        }
    }

    /** The header binder hands the button its account, story and view holder before anything else. */
    @Test
    fun theHeaderBinderHandsTheButtonItsStoryFirst() {
        val context = PatchContexts.of(standIns())

        context.holdBackStoryViews()

        val binder = context.mutableClassDefBy(BINDER).methods.single { it.name == "A06" }
        assertHeaderHooked("stand-in", binder, session = 1, item = 2, holder = 4)
    }

    @Test
    fun theStubsReachTheBatchTheStoreTheStoryAndItsView() {
        val context = PatchContexts.of(standIns())
        context.holdBackStoryViews()

        assertStubsFilled(context, BATCH, "$BATCH->stories:Ljava/util/HashMap;", "$STORE->A00($USER_SESSION)$STORE", "$STORE->A0O($BATCH)V", "$VIEW_HOLDER->itemView:$VIEW")
    }

    /** A filled stub answering something narrower than Object returns on its one way out (see FriendshipStatusHookTest). */
    @Test
    fun noStubJoinsTwoWaysAtOneReturn() {
        val context = PatchContexts.of(standIns())
        context.holdBackStoryViews()

        for ((type, names) in listOf(STORY_SEEN to listOf("emptyBatch", "seenStories", "send"), STORY_SEEN_BUTTON to listOf("storyId", "itemView"))) {
            for (stub in context.mutableClassDefBy(type).methods.filter { it.name in names }) {
                val code = stub.code()
                val first = code.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT || it.opcode == Opcode.RETURN_VOID }
                assertTrue("${stub.name}: no way out", first > 0)
                assertTrue("${stub.name}: a branch before its return", code.take(first).none { it is OffsetInstruction })
            }
        }
    }

    @Test
    fun aBuildWithoutTheRequestOrTheStoreFails() {
        assertRefused(standIns(request = false), "story seen request")
        assertRefused(standIns(storeReader = false), "pending story seen store")
    }

    @Test
    fun aBuildWithoutOneSendFails() {
        assertRefused(standIns(sends = 0), "found 0")
        assertRefused(standIns(sends = 2), "found 2")
    }

    /** The hook gives the send its batch in the parameter's register; written over first, the request is built from something else. */
    @Test
    fun aSendWritingOverItsBatchFails() = assertRefused(standIns(sendOverwrites = true), "writes over parameter 0")

    @Test
    fun aRequestNamingTheStoriesTwiceFails() = assertRefused(standIns(reelsTwice = true), "to name \"reels\" once")

    /** Stories read from anywhere but a map of the batch itself can't be filtered by changing the batch. */
    @Test
    fun storiesFromOutsideTheBatchFail() = assertRefused(standIns(storiesFromStatic = true), "from a map of its own batch")

    /** A branch landing between the read and the add could bring another value to "reels". */
    @Test
    fun aBranchIntoTheStoriesFails() = assertRefused(standIns(branchIntoStories = true), "lands between")

    /** A request that hands its batch to another method may send what that method reads. */
    @Test
    fun aRequestHandingItsBatchOnFails() = assertRefused(standIns(requestHandsBatchOn = true), "hands its batch on")

    /** A new batch that starts with something the request sends would send it along with the marked stories. */
    @Test
    fun aBatchThatStartsFullFails() {
        assertRefused(standIns(constructorFillsStories = true), "starts stories, which the seen request sends")
        assertRefused(standIns(constructorFillsModule = true), "starts module, which the seen request sends")
        assertRefused(standIns(constructorStartsStories = false), "doesn't start stories, its stories, as a new empty map")
    }

    @Test
    fun aBatchTheExtensionCantStartFails() = assertRefused(standIns(publicConstructor = false), "no public constructor taking nothing")

    @Test
    fun aStoreWithoutAGetterFails() = assertRefused(standIns(getter = false), "one public static getter")

    @Test
    fun aBuildWithoutTheHeaderBinderFails() = assertRefused(standIns(binder = false), "story header binder")

    @Test
    fun aBinderOfAnotherShapeFails() {
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, USER_SESSION, REEL_ITEM, HOLDER, "Z")), "to take one $USER_SESSION, found 2")
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, REEL_ITEM, VIEWER, VIEWER, "Z")), "to take one view holder, found 0")
    }

    /** Code put in front of an instruction a branch lands on is skipped by the branch. */
    @Test
    fun aBranchToTheBindersStartFails() = assertRefused(standIns(binderLoops = true), "lands on its first instruction")

    @Test
    fun aBinderWithoutThreeLocalsFails() = assertRefused(standIns(binderLocals = 2), "needs 3")

    @Test
    fun aStoryWithoutAnIdFails() = assertRefused(standIns(storyId = false), "has no public getId()")

    @Test
    fun anItemViewTheExtensionCantReadFails() = assertRefused(standIns(itemViewPublic = false), "isn't a public field")

    /**
     * On each declared build the send and the header binder are found and hooked and the stubs
     * filled, and the same classes with the binder copied, or without the story class, are refused
     * untouched.
     */
    @Test
    fun eachDeclaredBuildHooksTheSendAndTheHeader() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = fixtureClasses(bundle)
                val found = PatchContexts.of(classes).findStorySeen()
                assertEquals("${bundle.name}: the stories are a map of the batch", found.batch, found.reels.definingClass)
                assertEquals("${bundle.name}: the binder's account", USER_SESSION, found.binderParameters[found.session])
                assertEquals("${bundle.name}: the binder's story", REEL_ITEM, found.binderParameters[found.item])
                assertEquals("${bundle.name}: the item view", VIEW, found.itemView.type)

                val context = PatchContexts.of(classes)
                context.holdBackStoryViews()
                val store = classes.single { it.type == found.store }
                val before = store.methods.single { it.name == found.send && it.parameterTypes.map(Any::toString) == listOf(found.batch) }
                val send = context.mutableClassDefBy(found.store).methods.single { it.name == found.send && it.parameterTypes.map(Any::toString) == listOf(found.batch) }
                assertEquals("${bundle.name}: the send's size", before.code().size + 5, send.code().size)
                assertSendHooked("${bundle.name}: the send", send, found.batch)
                val binder = context.mutableClassDefBy(found.binder).methods.single { it.name == found.binderName && it.parameterTypes.map(Any::toString) == found.binderParameters }
                assertHeaderHooked("${bundle.name}: the header binder", binder, found.session, found.item, found.holder)
                assertStubsFilled(
                    context, found.batch, found.reels.toString(), "${found.store}->${found.getter}($USER_SESSION)${found.store}",
                    "${found.store}->${found.send}(${found.batch})V", found.itemView.toString(),
                )

                val binderClass = classes.single { it.type == found.binder }
                assertRefused(classes + copyOf(binderClass, "Lfixture/SecondBinder;"), "story header binder")
                assertRefused(classes.filter { it.type != REEL_ITEM }, "$REEL_ITEM isn't in this build")
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The classes the patch reads on [bundle]: those holding its four strings in one pass, then the
     * header binder's parameter types and the story class, then their superclasses up to the view
     * holder base, and the extension's two classes.
     */
    private fun fixtureClasses(bundle: java.io.File): List<ClassDef> {
        val strings = setOf("media/seen/?reel=%s&live_vod=0", "pending_reel_seen_states_", "ReelViewerItemBinder.bindHeaderViews", "itemView may not be null")
        val found = mutableMapOf<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { it in strings }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.code().any { it.string() in strings } }) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        val binder = found.values.flatMap { it.methods }.single { method -> method.code().any { it.string() == "ReelViewerItemBinder.bindHeaderViews" } }
        var wanted = (binder.parameterTypes.map(Any::toString) + REEL_ITEM).filter { it.startsWith("L") && it !in found }.toSet()
        repeat(4) {
            if (wanted.isEmpty()) return@repeat
            val loaded = FixtureDex.classes(bundle, wanted)
            found += loaded
            wanted = loaded.values.mapNotNull { it.superclass }.filter { it !in found && it != "Ljava/lang/Object;" }.toSet()
        }
        return (found.values + ExtensionDex.classDef(STORY_SEEN) + ExtensionDex.classDef(STORY_SEEN_BUTTON))
            .map { ImmutableClassDef.of(it) }.distinctBy { it.type }
    }

    /** The patch refuses [classes] saying [why], and nothing calls a hook or has a filled stub. */
    private fun assertRefused(classes: List<ClassDef>, why: String) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.holdBackStoryViews() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$PATCH: ") && why in refusal.message!!)
        assertUntouched(context)
    }

    private fun assertUntouched(context: BytecodePatchContext) {
        context.classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                val calls = method.code().count { it.referenceText() == TO_SEND || it.referenceText() == BIND_BUTTON }
                assertEquals("${classDef.type}->${method.name} calls a hook", 0, calls)
            }
        }
        fun Method.key() = "$name(${parameterTypes.joinToString("")})$returnType"
        for (type in listOf(STORY_SEEN, STORY_SEEN_BUTTON)) {
            val stock = ExtensionDex.classDef(type).methods.associate { it.key() to it.implementation?.instructions?.count() }
            context.classDefByOrNull(type)?.methods?.forEach {
                assertEquals("${it.key()} was filled", stock[it.key()], it.implementation?.instructions?.count())
            }
        }
    }

    /**
     * The send's first five instructions: a range call handing the batch to [TO_SEND], its answer
     * moved into the batch's own register, a return when it's null, and a cast back to the batch
     * where the non-null answer lands, right before the send's own first instruction. The hook is
     * called nowhere else in the send.
     */
    private fun assertSendHooked(what: String, send: Method, batch: String) {
        val code = send.code()
        assertEquals(
            "$what: the hook's opcodes",
            listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_NEZ, Opcode.RETURN_VOID, Opcode.CHECK_CAST),
            code.take(5).map { it.opcode },
        )
        assertEquals("$what: the hook called", TO_SEND, code[0].referenceText())
        val register = send.parameterRegisterNumber(0)
        val call = code[0] as RegisterRangeInstruction
        assertEquals("$what: the hook takes the batch", register to 1, call.startRegister to call.registerCount)
        assertEquals("$what: the answer replaces the batch", register, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the null check reads it", register, (code[2] as OneRegisterInstruction).registerA)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertEquals("$what: a batch lands on the cast", addresses[4], addresses[2] + (code[2] as OffsetInstruction).codeOffset)
        assertEquals("$what: cast back to the batch", batch, code[4].referenceText())
        assertEquals("$what: the cast is of the batch's register", register, (code[4] as OneRegisterInstruction).registerA)
        assertEquals("$what: calls of the hook", 1, code.count { it.referenceText() == TO_SEND })
        for ((index, instruction) in code.withIndex()) {
            if (index == 2 || instruction !is OffsetInstruction) continue
            val target = addresses[index] + instruction.codeOffset
            assertTrue("$what: the branch at $index lands in the hook", target !in addresses.take(5))
        }
    }

    /** The binder's first four instructions: its account, story and holder moved to v0 to v2 and handed to [BIND_BUTTON]. */
    private fun assertHeaderHooked(what: String, binder: Method, session: Int, item: Int, holder: Int) {
        val code = binder.code()
        assertEquals(
            "$what: the hook's opcodes",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC),
            code.take(4).map { it.opcode },
        )
        for ((local, parameter) in listOf(session, item, holder).withIndex()) {
            val move = code[local] as TwoRegisterInstruction
            assertEquals("$what: v$local is borrowed", local, move.registerA)
            assertEquals("$what: v$local holds parameter $parameter", binder.parameterRegisterNumber(parameter), move.registerB)
        }
        val call = code[3] as FiveRegisterInstruction
        assertEquals("$what: the hook called", BIND_BUTTON, code[3].referenceText())
        assertEquals("$what: the hook takes v0 to v2", listOf(0, 1, 2), listOf(call.registerC, call.registerD, call.registerE).take(call.registerCount))
        assertTrue("$what: three locals to borrow", binder.localRegisterCount() >= 3)
        assertEquals("$what: calls of the hook", 1, code.count { it.referenceText() == BIND_BUTTON })
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for ((index, instruction) in code.withIndex()) {
            if (instruction !is OffsetInstruction) continue
            val target = addresses[index] + instruction.codeOffset
            assertTrue("$what: the branch at $index lands in the hook", target !in addresses.take(4))
        }
    }

    private fun assertStubsFilled(context: BytecodePatchContext, batch: String, stories: String, getter: String, send: String, itemView: String) {
        fun reads(type: String, stub: String) = context.mutableClassDefBy(type).methods.single { it.name == stub }.code().mapNotNull { it.referenceText() }
        assertTrue(reads(STORY_SEEN, "emptyBatch").containsAll(listOf(batch, "$batch-><init>()V")))
        assertTrue(reads(STORY_SEEN, "seenStories").containsAll(listOf(batch, stories)))
        assertTrue(reads(STORY_SEEN, "send").containsAll(listOf(USER_SESSION, getter, batch, send)))
        assertTrue(reads(STORY_SEEN_BUTTON, "storyId").containsAll(listOf(REEL_ITEM, "$REEL_ITEM->getId()Ljava/lang/String;")))
        assertTrue(reads(STORY_SEEN_BUTTON, "itemView").contains(itemView))
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The batch, its request builder, its constructors and empty check; the store with its disk
     * reader, getter, send and the methods the patch leaves alone; the story header binder; the view
     * holder base and a holder two classes down; the story class; and the extension's two classes.
     */
    private fun standIns(
        request: Boolean = true,
        storeReader: Boolean = true,
        sends: Int = 1,
        sendOverwrites: Boolean = false,
        reelsTwice: Boolean = false,
        storiesFromStatic: Boolean = false,
        branchIntoStories: Boolean = false,
        requestHandsBatchOn: Boolean = false,
        constructorFillsStories: Boolean = false,
        constructorFillsModule: Boolean = false,
        constructorStartsStories: Boolean = true,
        publicConstructor: Boolean = true,
        getter: Boolean = true,
        binder: Boolean = true,
        binderParameters: List<String> = listOf(DELEGATE, USER_SESSION, REEL_ITEM, VIEWER, HOLDER, "Z"),
        binderLoops: Boolean = false,
        binderLocals: Int = 11,
        storyId: Boolean = true,
        itemViewPublic: Boolean = true,
    ): List<ClassDef> {
        val stories = if (storiesFromStatic) "sget-object v0, $BATCH->shared:Ljava/util/HashMap;" else "iget-object v0, p0, $BATCH->stories:Ljava/util/HashMap;"
        val builder = """
            ${if (branchIntoStories) "if-eqz p1, :late" else "nop"}
            const-string v0, "1"
            const-string v0, "media/seen/?reel=%s&live_vod=0"
            $stories
            invoke-static { v0 }, $BATCH->A00(Ljava/util/Map;)Ljava/lang/String;
            move-result-object v5
            iget-object v0, p0, $BATCH->skipped:Ljava/util/HashMap;
            invoke-static { v0 }, $BATCH->A00(Ljava/util/Map;)Ljava/lang/String;
            move-result-object v4
            :late
            new-instance v3, $REQUEST
            ${if (requestHandsBatchOn) "invoke-virtual { p0 }, $BATCH->A0A()Z" else "nop"}
            if-eqz v5, :skipped
            const-string v0, "reels"
            invoke-virtual { v3, v0, v5 }, $REQUEST->add(Ljava/lang/String;Ljava/lang/String;)V
            :skipped
            ${if (reelsTwice) "const-string v0, \"reels\"" else "nop"}
            if-eqz v4, :forced
            const-string v0, "reel_media_skipped"
            invoke-virtual { v3, v0, v4 }, $REQUEST->add(Ljava/lang/String;Ljava/lang/String;)V
            :forced
            iget-object v0, p0, $BATCH->forced:Ljava/util/List;
            const-string v1, "force_seen_story_ids"
            invoke-static { v0 }, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
            move-result-object v0
            invoke-virtual { v3, v1, v0 }, $REQUEST->add(Ljava/lang/String;Ljava/lang/String;)V
            iget-object v1, p0, $BATCH->module:Ljava/lang/String;
            if-eqz v1, :done
            const-string v0, "container_module"
            invoke-virtual { v3, v0, v1 }, $REQUEST->add(Ljava/lang/String;Ljava/lang/String;)V
            :done
            return-object v3
        """
        val synthetic = """
            invoke-static { }, Ljava/util/UUID;->randomUUID()Ljava/util/UUID;
            move-result-object v0
            invoke-virtual { v0 }, Ljava/lang/Object;->toString()Ljava/lang/String;
            move-result-object v4
            new-instance v3, Ljava/util/HashMap;
            invoke-direct { v3 }, Ljava/util/HashMap;-><init>()V
            ${if (constructorFillsStories) "invoke-virtual { v3, v4, v4 }, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" else "nop"}
            new-instance v2, Ljava/util/HashMap;
            invoke-direct { v2 }, Ljava/util/HashMap;-><init>()V
            const/4 v0, 0x1
            if-ge p1, v0, :sized
            const/4 p1, 0x1
            :sized
            new-instance v1, Ljava/util/ArrayList;
            invoke-direct { v1 }, Ljava/util/ArrayList;-><init>()V
            const/4 v0, 0x0
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            iput-object v4, p0, $BATCH->id:Ljava/lang/String;
            ${if (constructorStartsStories) "iput-object v3, p0, $BATCH->stories:Ljava/util/HashMap;" else "nop"}
            iput-object v2, p0, $BATCH->skipped:Ljava/util/HashMap;
            iput-object v1, p0, $BATCH->forced:Ljava/util/List;
            iput-object ${if (constructorFillsModule) "v4" else "v0"}, p0, $BATCH->module:Ljava/lang/String;
            return-void
        """
        val batchClass = classOf(
            BATCH,
            listOf(
                field(BATCH, "stories", "Ljava/util/HashMap;"), field(BATCH, "skipped", "Ljava/util/HashMap;"),
                field(BATCH, "forced", "Ljava/util/List;"), field(BATCH, "module", "Ljava/lang/String;"),
                field(BATCH, "id", "Ljava/lang/String;"), field(BATCH, "shared", "Ljava/util/HashMap;", static = true),
            ),
            listOfNotNull(
                method(BATCH, "<init>", emptyList(), "V", 3, """
                    const/16 v1, 0x3ff
                    const/4 v0, 0x0
                    invoke-direct { p0, v0, v1 }, $BATCH-><init>(II)V
                    return-void
                """, static = false, constructor = true, public = publicConstructor),
                method(BATCH, "<init>", listOf("I", "I"), "V", 8, synthetic, static = false, constructor = true),
                if (request) method(BATCH, "A04", listOf(OBJECT), REQUEST, 8, builder, static = false) else null,
                method(BATCH, "A00", listOf("Ljava/util/Map;"), "Ljava/lang/String;", 2, """
                    const/4 v0, 0x0
                    return-object v0
                """),
                method(BATCH, "A0A", emptyList(), "Z", 2, """
                    const/4 v0, 0x0
                    return v0
                """, static = false),
            ),
        )
        val sendBody = """
            ${if (sendOverwrites) "const/4 p1, 0x0" else "nop"}
            invoke-virtual { p1 }, $BATCH->A0A()Z
            move-result v0
            if-nez v0, :done
            const/4 v0, 0x0
            invoke-virtual { p1, v0 }, $BATCH->A04(Ljava/lang/Object;)$REQUEST
            :done
            return-void
        """
        val storeClass = classOf(
            STORE,
            emptyList(),
            listOfNotNull(
                if (storeReader) method(STORE, "A0L", emptyList(), "V", 2, """
                    const-string v0, "pending_reel_seen_states_"
                    const-string v0, "PendingReelSeenStateStore.deserializeFromDisk"
                    return-void
                """, static = false) else null,
                if (getter) method(STORE, "A00", listOf(USER_SESSION), STORE, 2, """
                    new-instance v0, $STORE
                    return-object v0
                """) else null,
                method(STORE, "A0J", listOf(OBJECT), REQUEST, 3, """
                    check-cast p1, $BATCH
                    const/4 v0, 0x0
                    invoke-virtual { p1, v0 }, $BATCH->A04(Ljava/lang/Object;)$REQUEST
                    move-result-object v0
                    return-object v0
                """, static = false),
                method(STORE, "A0P", listOf(BATCH), "V", 3, """
                    invoke-virtual { p1 }, $BATCH->A0A()Z
                    return-void
                """, static = false),
                method(STORE, "A0Q", listOf(BATCH), "V", 2, sendBody.replace("p1", "p0")),
            ) + (0 until sends).map { copy -> method(STORE, if (copy == 0) "A0O" else "A0R", listOf(BATCH), "V", 3, sendBody, static = false) },
        )
        val binderClass = if (binder) {
            val holderAt = binderParameters.indexOf(HOLDER).takeIf { it >= 0 } ?: 4
            classOf(
                BINDER,
                emptyList(),
                listOf(
                    method(BINDER, "A06", binderParameters, "V", binderLocals + binderParameters.size, """
                        :start
                        const-string v0, "ReelViewerItemBinder.bindHeaderViews"
                        move-object/from16 v1, p$holderAt
                        if-eqz v1, ${if (binderLoops) ":start" else ":done"}
                        invoke-virtual { v1 }, Ljava/lang/Object;->hashCode()I
                        :done
                        return-void
                    """),
                ),
            )
        } else {
            null
        }
        val viewHolder = classOf(
            VIEW_HOLDER,
            listOf(field(VIEW_HOLDER, "itemView", VIEW, public = itemViewPublic, final = true)),
            listOf(
                method(VIEW_HOLDER, "<init>", listOf(VIEW), "V", 4, """
                    invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                    if-nez p1, :kept
                    const-string v1, "itemView may not be null"
                    new-instance v0, Ljava/lang/IllegalArgumentException;
                    invoke-direct { v0, v1 }, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V
                    throw v0
                    :kept
                    iput-object p1, p0, $VIEW_HOLDER->itemView:$VIEW
                    return-void
                """, static = false, constructor = true),
            ),
            abstract = true,
        )
        val reelItem = classOf(
            REEL_ITEM,
            emptyList(),
            listOfNotNull(
                if (storyId) method(REEL_ITEM, "getId", emptyList(), "Ljava/lang/String;", 2, """
                    const/4 v0, 0x0
                    return-object v0
                """, static = false) else null,
            ),
        )
        return listOfNotNull(
            batchClass, storeClass, binderClass, viewHolder, reelItem,
            classOf(HOLDER_BASE, emptyList(), emptyList(), superclass = VIEW_HOLDER, abstract = true),
            classOf(HOLDER, emptyList(), emptyList(), superclass = HOLDER_BASE),
            classOf(VIEWER, emptyList(), emptyList()),
            ImmutableClassDef.of(ExtensionDex.classDef(STORY_SEEN)),
            ImmutableClassDef.of(ExtensionDex.classDef(STORY_SEEN_BUTTON)),
        )
    }

    private fun method(
        owner: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
        static: Boolean = true, constructor: Boolean = false, public: Boolean = true,
    ): Method {
        val flags = (if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or
            (if (static) AccessFlags.STATIC.value else 0) or
            (if (constructor) AccessFlags.CONSTRUCTOR.value else AccessFlags.FINAL.value)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun field(owner: String, name: String, type: String, static: Boolean = false, public: Boolean = true, final: Boolean = false) =
        ImmutableField(
            owner, name, type,
            (if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or (if (static) AccessFlags.STATIC.value else 0) or
                (if (final) AccessFlags.FINAL.value else 0),
            null, null, null,
        )

    private fun classOf(
        type: String, fields: List<ImmutableField>, methods: List<Method>, superclass: String = OBJECT, abstract: Boolean = false,
    ): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or (if (abstract) AccessFlags.ABSTRACT.value else AccessFlags.FINAL.value), superclass,
        null, null, null, fields, methods,
    )

    /** [classDef] under another name, its members moved with it. */
    private fun copyOf(classDef: ClassDef, type: String): ClassDef = ImmutableClassDef(
        type, classDef.accessFlags, classDef.superclass, classDef.interfaces, null, null,
        classDef.fields.map { ImmutableField(type, it.name, it.type, it.accessFlags, null, null, null) },
        classDef.methods.map {
            ImmutableMethod(type, it.name, it.parameters, it.returnType, it.accessFlags, null, null, it.implementation)
        },
    )

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private companion object {
        const val BATCH = "Lfixture/PendingReelSeenState;"
        const val STORE = "Lfixture/PendingReelSeenStateStore;"
        const val REQUEST = "Lfixture/Request;"
        const val BINDER = "Lfixture/ReelViewerItemBinder;"
        const val VIEW_HOLDER = "Lfixture/ViewHolder;"
        const val HOLDER_BASE = "Lfixture/HolderBase;"
        const val HOLDER = "Lfixture/ReelViewerHolder;"
        const val DELEGATE = "Lfixture/Delegate;"
        const val VIEWER = "Lfixture/Viewer;"
        const val OBJECT = "Ljava/lang/Object;"
        const val VIEW = "Landroid/view/View;"
    }
}
