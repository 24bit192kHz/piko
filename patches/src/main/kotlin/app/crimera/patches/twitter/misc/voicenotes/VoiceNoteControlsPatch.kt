/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.voicenotes

import app.crimera.patches.twitter.misc.extension.sharedExtensionPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/voicenotes/VoiceNoteControls;"
private const val VOICE_PLAYER_INTERFACE = "Lcom/x/dm/api/voicenote/b;"

private fun calls(m: com.android.tools.smali.dexlib2.iface.Method, name: String) =
    m.implementation?.instructions?.any {
        it.opcode == Opcode.INVOKE_VIRTUAL &&
            ((it as ReferenceInstruction).reference as MethodReference).let { r -> r.definingClass == "Landroid/media/MediaPlayer;" && r.name == name }
    } == true

@Suppress("unused")
val voiceNoteControlsPatch =
    bytecodePatch(
        name = "Voice note speed and seeking",
        description = "Adds playback speed (1x, 1.5x, 2x) and a seek bar for X Chat voice notes while one plays.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(sharedExtensionPatch)

        execute {
            var player: ClassDef? = null
            val completionListeners = mutableListOf<ClassDef>()
            classDefForEach { classDef ->
                if (VOICE_PLAYER_INTERFACE in classDef.interfaces && classDef.fields.any { it.type == "Landroid/media/MediaPlayer;" }) player = classDef
                if ("Landroid/media/MediaPlayer\$OnCompletionListener;" in classDef.interfaces && classDef.type.startsWith("Lcom/x/dms/voicenote/")) {
                    completionListeners += classDef
                }
            }
            val playerClass = player ?: throw PatchException("Voice note player not found")
            var starts = 0
            mutableClassDefBy(playerClass.type).methods.forEach { m ->
                if (m.implementation == null || (m.accessFlags and 0x8) != 0) return@forEach
                if (calls(m, "release")) {
                    m.addInstruction(0, "invoke-static {}, $EXTENSION_CLASS->onStopped()V")
                }
                m.instructions.withIndex().filter { (_, ins) ->
                    ins.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((ins as ReferenceInstruction).reference as MethodReference).let { r -> r.definingClass == "Landroid/media/MediaPlayer;" && r.name == "start" }
                }.map { it.index }.reversed().forEach { idx ->
                    m.addInstruction(idx + 1, "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onPlaying(Ljava/lang/Object;)V")
                    starts++
                }
            }
            if (starts == 0) throw PatchException("Voice note playback start not found")
            completionListeners.forEach { cls ->
                mutableClassDefBy(cls.type).methods.filter { it.name == "onCompletion" }.forEach {
                    it.addInstruction(0, "invoke-static {}, $EXTENSION_CLASS->onStopped()V")
                }
            }
        }
    }
