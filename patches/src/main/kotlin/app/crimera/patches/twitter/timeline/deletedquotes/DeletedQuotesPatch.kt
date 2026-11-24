/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.deletedquotes

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/DeletedQuotes;"

/** X 12.19.1: the post X is reading, the state of a post view, and the binder of the quote box. */
private const val JSON_TWEET = "Lcom/twitter/api/model/json/core/JsonApiTweet;"
private const val VIEW_STATE = "Lcom/twitter/tweetview/core/z;"
private const val QUOTE_BINDING = "Lcom/twitter/tweetview/core/ui/quote/j;"
private const val QUOTE_VIEW = "Lcom/twitter/tweetview/core/QuoteView;"
private const val QUOTED_RESULT = "Lcom/twitter/model/core/i0;"

@Suppress("unused")
val deletedQuotesPatch =
    patch(
        name = "Show deleted quotes",
        description = "When a quoted Tweet was deleted, the quote shows \"This Tweet has been deleted.\" " +
            "as Twitter used to, instead of disappearing.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch)

        execute {
            // 1. Reading a post: its quoted post (null when X left it out) goes through the extension.
            val quoted = mutableClassDefBy(JSON_TWEET).methods.firstOrNull {
                it.name == "v" && it.parameterTypes.isEmpty() && it.returnType == QUOTED_RESULT
            } ?: throw PatchException("Quoted post of a read post not found")
            quoted.apply {
                instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }.reversed().forEach { index ->
                    val r = (instructions[index] as OneRegisterInstruction).registerA
                    addInstructions(
                        index,
                        """
                        invoke-static { p0, v$r }, $EXTENSION_CLASS->quoted(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$r
                        check-cast v$r, $QUOTED_RESULT
                        """,
                    )
                }
            }

            // 2. A post view shows a quote: also when its quoted post was deleted.
            val hasQuote = mutableClassDefBy(VIEW_STATE).methods.firstOrNull {
                it.name == "f" && it.parameterTypes.isEmpty() && it.returnType == "Z"
            } ?: throw PatchException("Quote shown check not found")
            hasQuote.apply {
                instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }.reversed().forEach { index ->
                    val r = (instructions[index] as OneRegisterInstruction).registerA
                    addInstructions(
                        index,
                        """
                        invoke-static { v$r, p0 }, $EXTENSION_CLASS->hasQuote(ZLjava/lang/Object;)Z
                        move-result v$r
                        """,
                    )
                }
            }

            // 3. The quote box was bound and made visible: the line for a deleted quote, else none.
            val bind = mutableClassDefBy(QUOTE_BINDING).methods.firstOrNull { it.name == "accept" }
                ?: throw PatchException("Quote box binding not found")
            bind.apply {
                val list = instructions.toList()
                val bindIndex = list.indexOfFirst {
                    it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.let { m ->
                        m.definingClass == QUOTE_VIEW && m.name == "l"
                    } == true
                }
                if (bindIndex < 0) throw PatchException("Quote box bind call not found")
                val shownIndex = (bindIndex + 1 until list.size).first {
                    list[it].opcode == Opcode.INVOKE_VIRTUAL && list[it].getReference<MethodReference>()?.name == "setVisibility"
                }
                val box = (list[shownIndex] as FiveRegisterInstruction).registerC
                // The post: read from the view state's field "a" (a post) before the bind call.
                val tweet = (bindIndex downTo 0).map { list[it] }.first {
                    it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.let { f ->
                        f.definingClass == VIEW_STATE && f.type == "Lcom/twitter/model/core/e;"
                    } == true
                } as TwoRegisterInstruction
                addInstructions(
                    shownIndex + 1,
                    "invoke-static { v$box, v${tweet.registerA} }, $EXTENSION_CLASS->bound(Landroid/view/View;Ljava/lang/Object;)V",
                )
            }

            // 4. The line of a quote box without a post: the old Twitter line for a deleted quote.
            val line = mutableClassDefBy(QUOTE_VIEW).methods.firstOrNull {
                it.name == "getInterstitialString" && it.returnType == "Ljava/lang/String;"
            } ?: throw PatchException("Quote box line not found")
            line.apply {
                instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }.reversed().forEach { index ->
                    val r = (instructions[index] as OneRegisterInstruction).registerA
                    addInstructions(
                        index,
                        """
                        invoke-static { p0, v$r }, $EXTENSION_CLASS->interstitial(Landroid/view/View;Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$r
                        """,
                    )
                }
            }

            enableSettings("deletedQuotes")
        }
    }
