/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.fixsearch

import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "$PATCHES_DESCRIPTOR/search/FixLegacySearchPatch;"

// Legacy SearchTimeline GraphQL request builder: reads "query_source" from the request params.
private object SearchTimelineRequestFingerprint : Fingerprint(
    filters =
        listOf(
            string("search_graphql_migration_enabled"),
            string("search_timeline"),
            string("query_source"),
            methodCall(opcode = Opcode.INVOKE_VIRTUAL, returnType = "Ljava/lang/String;"),
            opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        ),
)

@Suppress("unused")
val fixLegacySearchPatch =
    bytecodePatch(
        name = "Fix legacy search",
        description = "Fixes search results failing to load (\"Oops, something went wrong\") in the original X UI. " +
            "X's server now requires the query_source variable, which the legacy UI leaves out for most searches.",
    ) {
        compatibleWith(COMPATIBILITY_X)

        execute {
            SearchTimelineRequestFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index + 1,
                        """
                        invoke-static {v$register}, $EXTENSION_CLASS_DESCRIPTOR->querySource(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                        """,
                    )
                }
            }
        }
    }
