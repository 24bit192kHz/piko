/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.verticalvideo

import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/VerticalVideoSize;"

/** The timeline's video card (X 12.19.1): it caps tall videos' height from the box's width. */
private const val VIDEO_CARD = "Lcom/twitter/content/host/media/m;"

@Suppress("unused")
val verticalVideoSizePatch =
    patch(
        name = "Steady vertical video size",
        description = "A tall video in the timeline no longer shows at full height for a moment before X shrinks it " +
            "to its usual size: X's size limit is applied before the video is first drawn.",
    ) {
        compatibleWith(COMPATIBILITY_X)

        execute {
            if (classDefByOrNull(VIDEO_CARD) == null) {
                println("WARNING: $VIDEO_CARD not found, vertical video size not patched")
                return@execute
            }
            var patched = 0
            mutableClassDefBy(VIDEO_CARD).methods.forEach { method ->
                if (method.implementation == null) return@forEach
                // X waits for the first layout of a box that has no width yet: box.addOnLayoutChangeListener(cap).
                method.instructions.withIndex().reversed().forEach { (index, instruction) ->
                    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@forEach
                    val ref = instruction.getReference<MethodReference>() ?: return@forEach
                    if (ref.name != "addOnLayoutChangeListener") return@forEach
                    val call = instruction as FiveRegisterInstruction
                    // Only X's own cap: a listener class of the card itself, created right before the call.
                    val created = (index - 1 downTo maxOf(0, index - 4)).map { method.instructions[it] }.firstOrNull {
                        it.opcode == Opcode.NEW_INSTANCE && (it as OneRegisterInstruction).registerA == call.registerD
                    } ?: return@forEach
                    val type = created.getReference<TypeReference>()?.type ?: return@forEach
                    if (!type.startsWith(VIDEO_CARD.removeSuffix(";") + "$")) return@forEach
                    method.addInstruction(
                        index + 1,
                        "invoke-static { v${call.registerC}, v${call.registerD} }, " +
                            "$EXTENSION_CLASS->capEarly(Landroid/view/View;Landroid/view/View\$OnLayoutChangeListener;)V",
                    )
                    patched++
                }
            }
            if (patched == 0) println("WARNING: X's vertical video size limit not found, not patched")
        }
    }
