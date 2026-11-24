/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.withheld

import app.crimera.patches.twitter.ads.timelineEntryHook.timelineEntryHookPatch
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.crimera.utils.changeFirstString
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/Withheld;"
private const val USER = "Lcom/twitter/api/model/json/core/RestJsonTwitterUser;"
private const val USER_MAPPER = "Lcom/twitter/api/model/json/core/RestJsonTwitterUser\$\$JsonObjectMapper;"

private fun placeholder(name: String) = object : Fingerprint(definingClass = EXTENSION_CLASS, name = name) {}

@Suppress("unused")
val hideWithheldPatch =
    patch(
        name = "Hide withheld accounts",
        description = "Accounts withheld in your country in response to a legal demand are left out of search " +
            "results and account lists instead of shown as an empty row with that notice.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch, timelineEntryHookPatch)

        execute {
            val parseField = mutableClassDefBy(USER_MAPPER).methods.firstOrNull {
                it.name == "parseField" && it.parameterTypes.firstOrNull() == USER
            } ?: throw PatchException("User reader not found")
            parseField.apply {
                val list = instructions.toList()
                fun fieldAfter(key: String, opcode: Opcode): Pair<Int, String> {
                    val keyIndex = list.indexOfFirst { it.getReference<StringReference>()?.string == key }
                    if (keyIndex < 0) throw PatchException("\"$key\" not read")
                    val index = (keyIndex until list.size).first {
                        list[it].opcode == opcode && list[it].getReference<FieldReference>()?.definingClass == USER
                    }
                    return index to list[index].getReference<FieldReference>()!!.name
                }
                val (_, idName) = fieldAfter("id_str", Opcode.IPUT_WIDE)
                val (scopeIndex, scopeName) = fieldAfter("withheld_scope", Opcode.IPUT_OBJECT)
                addInstruction(scopeIndex + 1, "invoke-static { p1 }, $EXTENSION_CLASS->scoped(Ljava/lang/Object;)V")
                placeholder("idFieldName").changeFirstString(idName)
                placeholder("scopeFieldName").changeFirstString(scopeName)
            }
            enableSettings("hideWithheld")
        }
    }
