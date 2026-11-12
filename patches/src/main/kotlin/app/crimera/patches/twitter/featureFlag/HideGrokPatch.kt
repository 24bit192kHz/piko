/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.featureFlag

import app.crimera.patches.twitter.featureFlag.featureFlagPatch.featureFlagPatch
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.enableSettings
import app.crimera.patches.twitter.utils.flagSettings
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
private const val GROK_EXTENSION = "$PATCHES_DESCRIPTOR/grok/HideGrokPatch;"
private const val TWEET_MAPPER = "Lcom/twitter/api/model/json/core/JsonApiTweet\$\$JsonObjectMapper;"

val hideGrokPatch =
    bytecodePatch(
        name = "Hide Grok",
        description = "Removes Grok buttons, analysis, badges, share/composer entries and Grok upsell prompts.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(featureFlagPatch, settingsPatch)

        execute {
            // Per-post Grok button flag from the server ("grok_analysis_button").
            val parseField = mutableClassDefBy(TWEET_MAPPER).methods.firstOrNull { m ->
                m.name == "parseField" && m.implementation?.instructions?.any { it.getReference<StringReference>()?.string == "grok_analysis_button" } == true
            } ?: throw PatchException("grok_analysis_button not parsed")
            val keyIndex = parseField.instructions.indexOfFirst { it.getReference<StringReference>()?.string == "grok_analysis_button" }
            val putIndex = (keyIndex until parseField.instructions.size).first { parseField.instructions[it].opcode == Opcode.IPUT_BOOLEAN }
            val register = (parseField.instructions[putIndex] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA
            parseField.addInstructions(
                putIndex,
                """
                invoke-static {v$register}, $GROK_EXTENSION->grokAnalysisButton(Z)Z
                move-result v$register
                """,
            )

            flagSettings("hideGrok")
            enableSettings("hideGrok")
        }
    }
