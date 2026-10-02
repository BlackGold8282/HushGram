/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep a seek bar on Reels"
internal const val REEL_SEEK_BAR = "$EXTENSION_PACKAGE/reels/ReelSeekBar;"
internal const val MIN_SECONDS = "$REEL_SEEK_BAR->minSeconds(J)J"
internal const val LAZY = "$REEL_SEEK_BAR->lazy(I)Z"
internal const val PROGRESS = "$REEL_SEEK_BAR->progress(Landroid/widget/SeekBar;I)V"

/**
 * The shortest ordinary reel, in seconds, that gets Instagram's attached seek bar: a parameter of
 * the server setting ig_android_iv_video_scrubber. 449 reads it three times: in the check of
 * whether a reel gets the bar, in the check of whether its bar is the hidden kind, and where the
 * Reels progress controller keeps its limits. The first two read an ad's own minimum in the same
 * place, picked in a branch.
 */
internal const val ORGANIC_MIN_SECONDS = 0x82092d002914b2L
internal const val ORGANIC_MIN_SECONDS_READS = 3

/**
 * Whether a short ordinary reel's bar is the hidden kind, shown only while you hold the reel. 449
 * reads it once, where the seek bar row's state is worked out, in the same place as the ads' flag.
 */
internal const val ORGANIC_LAZY = 0x81092d001033caL
internal const val ORGANIC_LAZY_READS = 1

/** The markers, after Instagram's release prefix, of the methods that prove the reads are the seek bar's. */
internal const val SHOULD_SHOW_ATTACHED = "ClipsExperimentUtil_shouldShowAttachedScrubber"
internal const val CALCULATE_SHOULD_SHOW = "ClipsItemUseCase_calculateShouldShowAttachedScrubber"
internal const val SCRUBBER_ROW_STATE = "ClipsScrubberRowUseCase_getUiState"

/** Instagram's reel seek bar, a kept class, and the listener method the label hook goes in. */
internal const val SEEK_BAR = "Lcom/instagram/ui/mediaactions/VideoScrubberSeekBar;"
internal const val PROGRESS_CHANGED = "onProgressChanged"
private const val ANDROID_SEEK_BAR = "Landroid/widget/SeekBar;"
private val PROGRESS_CHANGED_PARAMETERS = listOf(ANDROID_SEEK_BAR, "I", "Z")

/**
 * Keeps Instagram's seek bar under every reel, with the time played and the reel's length. Off in
 * the default selection: it changes how every reel looks, so it's the user's pick. Asked for in #10.
 *
 * Instagram 449 decides per reel whether to draw its seek bar under it from a server minimum
 * length, and on short reels draws none, or one hidden until a hold. The patch answers the minimum
 * for ordinary reels as one second and the hidden kind as off, through the extension, at each of
 * Instagram's reads of them; where a read also serves the ads' value, only the ordinary reel's
 * branch gets the answer. The bar's own onProgressChanged hands the extension the bar and its
 * position, for the time label.
 *
 * Every read is found by its server id and counted, each is checked against Instagram's own markers
 * for the seek bar, and the bar's class and method are checked, all before anything changes. A
 * build that differs stops the patch naming what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val reelSeekBarPatch = bytecodePatch(
    name = "Keep a seek bar on Reels",
    description = "Keeps Instagram's seek bar under every reel, short ones too, with the time played and the reel's " +
        "length above it, like 0:10 / 0:55. Ads keep Instagram's own rules.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelSeekBar")
        applyReelSeekBar(findReelSeekBarSites())
        enableStatus("reelSeekBar")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The three reads of the minimum, the read of the hidden kind, and the seek bar's listener method. */
internal class ReelSeekBarSites(
    val lengths: List<FlagLoad>,
    val lazy: FlagLoad,
    val progress: Method,
)

/**
 * Finds every read of [ORGANIC_MIN_SECONDS] and [ORGANIC_LAZY] and checks there are exactly
 * [ORGANIC_MIN_SECONDS_READS] and [ORGANIC_LAZY_READS], then that they're the seek bar's: the
 * method marked [SHOULD_SHOW_ATTACHED] reads the minimum and the one marked
 * [CALCULATE_SHOULD_SHOW] calls it, and the one marked [SCRUBBER_ROW_STATE] reads the hidden kind
 * and calls a method that reads the minimum. Then finds [SEEK_BAR]'s one [PROGRESS_CHANGED]. Fails
 * before anything changes when any of it isn't so.
 */
internal fun BytecodePatchContext.findReelSeekBarSites(): ReelSeekBarSites {
    val lengths = findFlagLoads(PATCH, ORGANIC_MIN_SECONDS, "J")
    if (lengths.size != ORGANIC_MIN_SECONDS_READS) {
        refuse(
            "expected $ORGANIC_MIN_SECONDS_READS reads of the shortest reel with a seek bar " +
                "(${ORGANIC_MIN_SECONDS.toString(16)}), found ${lengths.size}",
        )
    }
    val lazies = findFlagLoads(PATCH, ORGANIC_LAZY, "Z")
    if (lazies.size != ORGANIC_LAZY_READS) {
        refuse("expected $ORGANIC_LAZY_READS read of the hidden seek bar flag (${ORGANIC_LAZY.toString(16)}), found ${lazies.size}")
    }
    val lazy = lazies.single()

    val marked = markedMethods(listOf(SHOULD_SHOW_ATTACHED, CALCULATE_SHOULD_SHOW, SCRUBBER_ROW_STATE))
    val attached = marked.getValue(SHOULD_SHOW_ATTACHED)
    if (lengths.none { it.signature() == attached.signature() }) {
        refuse("${attached.signature()} ($SHOULD_SHOW_ATTACHED) doesn't read the shortest reel with a seek bar")
    }
    val calculate = marked.getValue(CALCULATE_SHOULD_SHOW)
    if (attached.signature() !in calculate.calledSignatures()) {
        refuse("${calculate.signature()} ($CALCULATE_SHOULD_SHOW) doesn't ask ${attached.signature()}")
    }
    val row = marked.getValue(SCRUBBER_ROW_STATE)
    if (lazy.signature() != row.signature()) {
        refuse("the hidden seek bar flag is read in ${lazy.signature()}, not in ${row.signature()} ($SCRUBBER_ROW_STATE)")
    }
    if (lengths.none { it.signature() in row.calledSignatures() }) {
        refuse("${row.signature()} ($SCRUBBER_ROW_STATE) asks no method reading the shortest reel with a seek bar")
    }
    return ReelSeekBarSites(lengths, lazy, findProgressChanged())
}

/**
 * [SEEK_BAR]'s [PROGRESS_CHANGED]: the class has to be a SeekBar, and to have exactly one method of
 * that name, an instance `(SeekBar, int, boolean)void` with a body whose position parameter an
 * invoke can name.
 */
internal fun BytecodePatchContext.findProgressChanged(): Method {
    val bar = classDefByOrNull(SEEK_BAR) ?: refuse("this Instagram build has no $SEEK_BAR")
    var parent: String? = bar.superclass
    val seen = mutableSetOf<String>()
    while (parent != ANDROID_SEEK_BAR) {
        if (parent == null || !seen.add(parent)) refuse("$SEEK_BAR isn't a $ANDROID_SEEK_BAR")
        parent = classDefByOrNull(parent)?.superclass ?: refuse("$SEEK_BAR extends $parent, which isn't a $ANDROID_SEEK_BAR")
    }
    val named = bar.methods.filter { it.name == PROGRESS_CHANGED }
    val method = named.singleOrNull() ?: refuse("expected one $PROGRESS_CHANGED in $SEEK_BAR, found ${named.size}")
    if (method.parameterTypes.map(CharSequence::toString) != PROGRESS_CHANGED_PARAMETERS || method.returnType != "V" ||
        AccessFlags.STATIC.isSet(method.accessFlags) || method.implementation == null
    ) {
        refuse("$SEEK_BAR's $PROGRESS_CHANGED isn't an instance (SeekBar, int, boolean)void with a body")
    }
    if (method.parameterRegisterNumber(1) > 15) {
        refuse("$SEEK_BAR's $PROGRESS_CHANGED keeps the position in a register past v15")
    }
    return method
}

/**
 * Answers each read of the minimum through [MIN_SECONDS] and the read of the hidden kind through
 * [LAZY], then puts the call to [PROGRESS] first in the seek bar's [PROGRESS_CHANGED], handing it
 * the bar (`this`) and the position.
 */
internal fun BytecodePatchContext.applyReelSeekBar(sites: ReelSeekBarSites) {
    answerFlagLoads(sites.lengths.map { it to MIN_SECONDS } + (sites.lazy to LAZY))
    val progress = sites.progress
    mutableClassDefBy(progress.definingClass).methods.single {
        it.name == progress.name && it.parameterTypes.map(CharSequence::toString) == PROGRESS_CHANGED_PARAMETERS
    }.addInstructions(0, "invoke-static { p0, p2 }, $PROGRESS")
}

/** The one method outside the extension holding each marker; fails when a marker isn't held by exactly one. */
private fun BytecodePatchContext.markedMethods(wanted: List<String>): Map<String, Method> {
    val found = wanted.associateWith { mutableListOf<Method>() }
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            method.markers().toSet().forEach { marker -> found[marker]?.add(method) }
        }
    }
    return found.mapValues { (marker, methods) ->
        methods.singleOrNull() ?: refuse("expected one method marked $marker, found ${methods.size}")
    }
}

private fun FlagLoad.signature() = "$type->$name(${parameters.joinToString("")})$returnType"

private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.calledSignatures(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference) }
    ?.map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
    ?.toSet() ?: emptySet()
