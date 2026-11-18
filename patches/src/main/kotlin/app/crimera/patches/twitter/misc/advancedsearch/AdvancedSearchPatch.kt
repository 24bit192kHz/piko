/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.advancedsearch

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/AdvancedSearch;"
private const val EMPTY_STATE = "Lcom/twitter/app/legacy/list/b;"

/** The list's empty state: show() makes it visible, a() hides it. */
private class EmptyStateFingerprint(name: String) : Fingerprint(
    definingClass = EMPTY_STATE,
    name = name,
    returnType = "V",
    parameters = listOf(),
)

@Suppress("unused")
val advancedSearchPatch =
    patch(
        name = "Advanced search",
        description = "Shows X's web \"Advanced search\" as a native form on the search screen's empty Explore page " +
            "(instead of \"Nothing to see here\"), under the normal search box.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch)

        execute {
            listOf("show" to "onEmptyShown", "a" to "onEmptyHidden").forEach { (name, hook) ->
                EmptyStateFingerprint(name).method.apply {
                    val index = instructions.indexOfFirst {
                        it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.let { f ->
                            f.definingClass == EMPTY_STATE && f.type == "Landroid/view/View;"
                        } == true
                    }
                    if (index < 0) throw PatchException("Empty state view not found in $name()")
                    val register = (instructions.elementAt(index) as OneRegisterInstruction).registerA
                    addInstruction(index + 1, "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->$hook(Landroid/view/View;)V")
                }
            }
            enableSettings("advancedSearch")
        }
    }
