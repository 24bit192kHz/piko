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
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
private const val TWEET = "Lcom/twitter/model/core/e;"
/** X 12.19.1: deleting a post, a post page, and what the page shows when its post could not be loaded. */
private const val DELETE_REQUEST = "Lcom/twitter/api/legacy/request/tweet/j;"
private const val POST_PAGE = "Lcom/twitter/tweetdetail/a0;"
private const val POST_PAGE_LOADED = "Lcom/twitter/tweetdetail/a0\$a;"
private const val POST_PAGE_MESSAGES = "Lcom/twitter/tweetdetail/b0;"
private const val LIST_MESSAGE = "Lcom/twitter/ui/list/e;"
private const val POST_PAGE_LIST = "Lcom/twitter/tweetdetail/w0;"

@Suppress("unused")
val deletedQuotesPatch =
    patch(
        name = "Show deleted quotes",
        description = "When a quoted Tweet was deleted, the quote shows \"This Tweet has been deleted.\" " +
            "as Twitter used to, instead of disappearing, and so does the page of a deleted Tweet.",
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
                        f.definingClass == VIEW_STATE && f.type == TWEET
                    } == true
                } as TwoRegisterInstruction
                addInstructions(
                    shownIndex + 1,
                    "invoke-static { v$box, v${tweet.registerA} }, $EXTENSION_CLASS->bound(Landroid/view/View;Ljava/lang/Object;)V",
                )
                // The quoted post handed to the box: none for a deleted one (X may have it from its cache).
                val quotedReg = (list[bindIndex] as FiveRegisterInstruction).registerD
                addInstructions(
                    bindIndex,
                    """
                    invoke-static { v${tweet.registerA}, v$quotedReg }, $EXTENSION_CLASS->quoteToShow(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$quotedReg
                    check-cast v$quotedReg, $TWEET
                    """,
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

            // 5. X deleted posts after its server did: remembered as deleted.
            mutableClassDefBy(DELETE_REQUEST).methods.firstOrNull {
                it.name == "k0" && it.parameterTypes.size == 3 && it.parameterTypes[2] == "Ljava/util/List;"
            }?.addInstructions(0, "invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->deletedHere(Ljava/util/List;)V")
                ?: throw PatchException("Local removal of deleted posts not found")

            // 6. A post page shows its post.
            mutableClassDefBy(POST_PAGE).methods.firstOrNull {
                it.name == "H3" && it.parameterTypes.size == 1 && it.parameterTypes[0] == TWEET
            }?.addInstructions(0, "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->held(Ljava/lang/Object;Ljava/lang/Object;)V")
                ?: throw PatchException("Post page bind not found")

            // 7. A post page could not load its post: "This Tweet has been deleted." when that is known.
            mutableClassDefBy(POST_PAGE_LOADED).methods.firstOrNull { it.name == "c" && it.parameterTypes.size == 1 }?.apply {
                val list = instructions.toList()
                val call = list.indexOfFirst {
                    it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.let { m ->
                        m.definingClass == POST_PAGE_MESSAGES && m.name == "b" && m.returnType == LIST_MESSAGE
                    } == true
                }
                if (call < 0 || list[call + 1].opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("Post page message call not found")
                val response = (list[call] as FiveRegisterInstruction).registerD
                val r = (list[call + 1] as OneRegisterInstruction).registerA
                addInstructions(
                    call + 2,
                    """
                    invoke-static { v$r, p0, v$response }, $EXTENSION_CLASS->pageState(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$r
                    check-cast v$r, $LIST_MESSAGE
                    """,
                )
            } ?: throw PatchException("Post page load handler not found")

            // 8. The post page's list could not load: no "Retry" on a page that says its post was deleted.
            mutableClassDefBy(POST_PAGE_LIST).methods.firstOrNull {
                it.name == "H0" && it.parameterTypes.size == 1 && it.parameterTypes[0] == "I"
            }?.addInstructionsWithLabels(
                0,
                """
                invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->quietError(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :show
                return-void
                :show
                nop
                """,
            ) ?: throw PatchException("Post page list error not found")

            enableSettings("deletedQuotes")
        }
    }
