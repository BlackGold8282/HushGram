/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock

/** Owned pending/in-flight maps and a snapshot retry loop shaped like the declared native build. */
internal fun storyQueueBase(owner: String, request: String, session: Boolean = true): ImmutableClassDef {
    val lock = "$owner->lock:Ljava/lang/Object;"
    val pending = "$owner->pending:Ljava/util/LinkedHashMap;"
    val flight = "$owner->flight:Ljava/util/Map;"
    val methods = mutableListOf<Method>()
    fun method(name: String, parameters: List<String>, returns: String, registers: Int, body: String,
        constructor: Boolean = false, monitor: Boolean = false, protected: Pair<Int, Int>? = null) {
        methods += storyQueueMethod(owner, name, parameters, returns, registers, body, constructor, monitor, protected)
    }
    method("<init>", listOf(USER_SESSION), "V", 3, """
        invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
        iput-object p1, p0, $owner->account:$USER_SESSION
        new-instance v0, Ljava/util/LinkedHashMap;
        invoke-direct { v0 }, Ljava/util/LinkedHashMap;-><init>()V
        iput-object v0, p0, $pending
        new-instance v0, Ljava/util/HashMap;
        invoke-direct { v0 }, Ljava/util/HashMap;-><init>()V
        iput-object v0, p0, $flight
        new-instance v0, Ljava/lang/Object;
        invoke-direct { v0 }, Ljava/lang/Object;-><init>()V
        iput-object v0, p0, $lock
        return-void
    """, constructor = true)
    if (session) method("A0H", emptyList(), USER_SESSION, 2, """
        iget-object v0, p0, $owner->account:$USER_SESSION
        return-object v0
    """)
    methods += ImmutableMethod(owner, "A0J", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), request,
        AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
    method("A0K", emptyList(), "Ljava/lang/Integer;", 2, """
        const/4 v0, 0x0
        return-object v0
    """)
    method("A03", emptyList(), "I", 4, """
        iget-object v2, p0, $lock
        monitor-enter v2
        iget-object v0, p0, $pending
        invoke-virtual { v0 }, Ljava/util/AbstractMap;->size()I
        move-result v1
        iget-object v0, p0, $flight
        invoke-interface { v0 }, Ljava/util/Map;->size()I
        move-result v0
        add-int/2addr v1, v0
        monitor-exit v2
        return v1
        move-exception v0
        monitor-exit v2
        throw v0
    """, protected = 2 to 9)
    method("A05", emptyList(), "Ljava/util/ArrayList;", 4, """
        iget-object v2, p0, $lock
        monitor-enter v2
        iget-object v0, p0, $pending
        invoke-virtual { v0 }, Ljava/util/AbstractMap;->keySet()Ljava/util/Set;
        move-result-object v1
        new-instance v0, Ljava/util/ArrayList;
        invoke-direct { v0, v1 }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
        monitor-exit v2
        return-object v0
        move-exception v0
        monitor-exit v2
        throw v0
    """, protected = 2 to 7)
    method("A04", listOf("Ljava/lang/String;"), "Ljava/lang/Object;", 5, """
        iget-object v2, p0, $lock
        monitor-enter v2
        iget-object v1, p0, $pending
        invoke-virtual { v1, p1 }, Ljava/util/AbstractMap;->containsKey(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :get
        iget-object v1, p0, $flight
        :get
        invoke-interface { v1, p1 }, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v0
        monitor-exit v2
        return-object v0
        move-exception v0
        monitor-exit v2
        throw v0
    """, protected = 2 to 9)
    method("A0G", listOf("Ljava/lang/String;"), "Z", 6, """
        const/4 v2, 0x0
        invoke-static { p1, v2 }, Lfixture/Checks;->parameter(Ljava/lang/Object;I)V
        iget-object v3, p0, $lock
        monitor-enter v3
        iget-object v1, p0, $pending
        invoke-virtual { v1, p1 }, Ljava/util/AbstractMap;->containsKey(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :claim
        monitor-exit v3
        return v2
        :claim
        iget-object v2, p0, $flight
        invoke-virtual { v1, p1 }, Ljava/util/AbstractMap;->remove(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v1
        const-string v0, "null cannot be cast to non-null type T of com.instagram.store.PendingActionStore"
        invoke-static { v1, v0 }, Lfixture/Checks;->present(Ljava/lang/Object;Ljava/lang/String;)V
        invoke-interface { v2, p1, v1 }, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
        monitor-exit v3
        const/4 v2, 0x1
        return v2
        move-exception v0
        monitor-exit v3
        throw v0
    """, protected = 4 to 16)
    method("A0C", listOf("Ljava/lang/String;"), "V", 4, """
        iget-object v1, p0, $lock
        monitor-enter v1
        iget-object v0, p0, $flight
        invoke-interface { v0, p1 }, Ljava/util/Map;->remove(Ljava/lang/Object;)Ljava/lang/Object;
        monitor-exit v1
        return-void
        move-exception v0
        monitor-exit v1
        throw v0
    """, protected = 2 to 4)
    method("A0I", emptyList(), "V", 10, """
        move-object v6, p0
        monitor-enter v6
        invoke-virtual { p0 }, $owner->A03()I
        invoke-virtual { p0 }, $owner->A05()Ljava/util/ArrayList;
        move-result-object v0
        invoke-virtual { v0 }, Ljava/util/AbstractCollection;->iterator()Ljava/util/Iterator;
        move-result-object v2
        invoke-static { v2 }, Lfixture/Checks;->present(Ljava/lang/Object;)V
        :next
        invoke-interface { v2 }, Ljava/util/Iterator;->hasNext()Z
        move-result v0
        if-eqz v0, :done
        invoke-interface { v2 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
        move-result-object v7
        check-cast v7, Ljava/lang/String;
        invoke-virtual { p0, v7 }, $owner->A04(Ljava/lang/String;)Ljava/lang/Object;
        move-result-object v5
        if-eqz v5, :next
        invoke-virtual { p0, v7 }, $owner->A0G(Ljava/lang/String;)Z
        move-result v0
        if-eqz v0, :next
        invoke-virtual { p0, v5 }, $owner->A0J(Ljava/lang/Object;)$request
        move-result-object v1
        invoke-virtual { p0 }, $owner->A0K()Ljava/lang/Integer;
        move-result-object v4
        const/4 v8, 0x0
        new-instance v3, Lfixture/RetryCallback;
        invoke-direct/range { v3 .. v8 }, Lfixture/RetryCallback;-><init>(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;I)V
        invoke-virtual { v1, v3 }, $request->callback(Lfixture/RetryCallback;)V
        invoke-virtual { p0 }, $owner->A0H()$USER_SESSION
        move-result-object v0
        invoke-static { v0 }, Lfixture/Scheduler;->forAccount($USER_SESSION)Lfixture/Scheduler;
        move-result-object v0
        invoke-virtual { v0, v1 }, Lfixture/Scheduler;->send($request)V
        goto :next
        :done
        monitor-exit v6
        return-void
        move-exception v0
        monitor-exit v6
        throw v0
    """, monitor = true, protected = 2 to 34)
    val fields = listOf("lock" to "Ljava/lang/Object;", "pending" to "Ljava/util/LinkedHashMap;",
        "flight" to "Ljava/util/Map;", "account" to USER_SESSION).map { (name, type) ->
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
    }
    return ImmutableClassDef(owner, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
        emptyList(), null, null, fields, methods)
}

internal fun storyQueueReader(store: String, owner: String): Method = storyQueueMethod(store, "A0L", emptyList(), "V", 10, """
    const-string v4, "pending_reel_seen_states_"
    const-string v0, "PendingReelSeenStateStore.deserializeFromDisk"
    invoke-virtual { p0 }, $owner->A0H()$USER_SESSION
    move-result-object v6
    iget-object v5, p0, $store->storage:Lfixture/SeenStorage;
    invoke-virtual { p0 }, $owner->A0I()V
    iget-object v0, v6, $USER_SESSION->userId:Ljava/lang/String;
    invoke-static { v4, v0 }, Lfixture/Strings;->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-virtual { v5, v0 }, Lfixture/SeenStorage;->delete(Ljava/lang/String;)V
    return-void
""")

internal fun storyQueueMethod(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
    constructor: Boolean = false, monitor: Boolean = false, protected: Pair<Int, Int>? = null): ImmutableMethod {
    val flags = AccessFlags.PUBLIC.value or (if (constructor) AccessFlags.CONSTRUCTOR.value else AccessFlags.FINAL.value) or
        (if (monitor) AccessFlags.DECLARED_SYNCHRONIZED.value else 0)
    val method = MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
        flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null)))
    method.addInstructionsWithLabels(0, body.trimIndent())
    val code = method.implementation!!.instructions.toList()
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    val blocks = protected?.let { (start, end) -> listOf(ImmutableTryBlock(addresses[start], addresses[end] - addresses[start],
        listOf(ImmutableExceptionHandler(null, addresses[code.size - 3])))) }.orEmpty()
    return ImmutableMethod(owner, name, method.parameters, returns, flags, null, null,
        ImmutableMethodImplementation(registers, code, blocks, null))
}
