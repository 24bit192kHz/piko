/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 *
 * Feature after the "Not interested" button of the user's Control Panel for Twitter fork
 * (https://github.com/insin/control-panel-for-twitter, MIT License, Copyright (c) 2019 Jonny Buchanan).
 */

package app.crimera.patches.twitter.misc.notinterested

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.misc.shareMenu.nativeDownloader.inlineDownloadButtonPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.crimera.utils.changeFirstString
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/DislikeButton;"
private const val CURATION_BINDER = "Lcom/twitter/tweetview/core/ui/curation/CurationViewDelegateBinder;"
private const val CURATION_HANDLER = "Lcom/twitter/tweetview/core/j;"
private const val TIMELINE_TWEET = "Lcom/twitter/model/timeline/n2;"
private const val CONTEXTUAL_TWEET = "Lcom/twitter/model/core/e;"

private const val SOCIAL_CONTEXT_BINDER = "Lcom/twitter/tweetview/core/ui/curation/CurationSocialContextDelegateBinder;"
private const val CURATION_VIEW = "Lcom/twitter/tweetview/core/ui/curation/h;"

/**
 * The timeline post's menu binder runs this for every state of the post (the state carries the
 * tweet and its timeline item); the lambda holds the binder, which holds the menu action handler.
 */
private object CurationStateFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("home_timeline_feedback_immediate_dismiss_enabled"),
    custom = { _, classDef ->
        classDef.fields.any { it.type == CURATION_VIEW } && classDef.fields.any { it.type == SOCIAL_CONTEXT_BINDER }
    },
)

/** The older menu binder's "how should the caret look" method: it reads the item's feedback actions. */
private object CurationCaretTypeFingerprint : Fingerprint(
    definingClass = CURATION_BINDER,
    returnType = "I",
    parameters = listOf(TIMELINE_TWEET),
    strings = listOf("DontLike"),
)

private fun placeholder(name: String) = object : Fingerprint(definingClass = EXTENSION_CLASS, name = name) {}

private val notInterestedResourcePatch =
    patch {
        execute {
            copyResources("twitter/notinterested", ResourceGroup("drawable", "piko_ic_not_interested.xml"))
        }
    }

@Suppress("unused")
val notInterestedButtonPatch =
    patch(
        name = "Not interested button",
        description = "Adds a \"Not interested\" button to the action bar of posts X offers that feedback for " +
            "(the For you timeline): it marks the post \"Not interested\" with \"This post isn't relevant\" and hides it.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch, inlineDownloadButtonPatch, notInterestedResourcePatch)

        execute {
            val method = CurationCaretTypeFingerprint.method
            val refs = method.instructions.toList()
            fun field(predicate: (FieldReference) -> Boolean) =
                refs.firstNotNullOfOrNull { it.getReference<FieldReference>()?.takeIf(predicate) }
            fun call(predicate: (MethodReference) -> Boolean) =
                refs.firstNotNullOfOrNull { it.getReference<MethodReference>()?.takeIf(predicate) }

            val tweetField = field { it.type == CONTEXTUAL_TWEET }
                ?: throw PatchException("Timeline item's tweet field not found")
            val hasFeedback = call { it.returnType == "Z" && it.parameterTypes.isEmpty() && it.definingClass != CONTEXTUAL_TWEET && it.definingClass != "Ljava/lang/String;" }
                ?: throw PatchException("Timeline item's has-feedback method not found")
            val feedbackInfo = call { it.definingClass == hasFeedback.definingClass && it.returnType.startsWith("L") && it.parameterTypes.isEmpty() }
                ?: throw PatchException("Timeline item's feedback info method not found")
            val feedbackList = field { it.definingClass == feedbackInfo.returnType && it.type == "Ljava/util/List;" }
                ?: throw PatchException("Feedback action list not found")
            val dontLikeIndex = refs.indexOfFirst { it.getReference<StringReference>()?.string == "DontLike" }
            val actionType = refs.take(dontLikeIndex).lastOrNull { it.opcode == Opcode.IGET_OBJECT }
                ?.getReference<FieldReference>()?.takeIf { it.type == "Ljava/lang/String;" }
                ?: throw PatchException("Feedback action type field not found")

            val binderClass = classDefByOrNull(SOCIAL_CONTEXT_BINDER) ?: throw PatchException("$SOCIAL_CONTEXT_BINDER not found")
            val handlerField = binderClass.fields.firstOrNull { it.type == CURATION_HANDLER }
                ?: throw PatchException("Menu action handler field not found")
            val stateMethod = CurationStateFingerprint.method
            val stateClass = classDefByOrNull(stateMethod.definingClass) ?: throw PatchException("Menu state lambda not found")
            val lambdaBinderField = stateClass.fields.first { it.type == SOCIAL_CONTEXT_BINDER }
            // The state (the lambda casts its argument to it) holds the tweet and the timeline item.
            val stateType = stateMethod.instructions.firstNotNullOfOrNull {
                if (it.opcode == Opcode.CHECK_CAST) it.getReference<com.android.tools.smali.dexlib2.iface.reference.TypeReference>()?.type else null
            } ?: throw PatchException("Menu state type not found")
            val stateClassDef = classDefByOrNull(stateType) ?: throw PatchException("$stateType not found")
            val stateTweetField = stateClassDef.fields.firstOrNull { it.type == CONTEXTUAL_TWEET }
                ?: throw PatchException("State tweet field not found")
            val stateItemField = stateClassDef.fields.firstOrNull { it.type == TIMELINE_TWEET }
                ?: throw PatchException("State item field not found")
            val dismiss = (classDefByOrNull(CURATION_HANDLER) ?: throw PatchException("$CURATION_HANDLER not found")).methods.firstOrNull {
                it.parameterTypes.map { p -> p.toString() } == listOf(CONTEXTUAL_TWEET, TIMELINE_TWEET, actionType.definingClass) &&
                    it.returnType == "V"
            } ?: throw PatchException("Feedback action method of the menu handler not found")

            placeholder("handlerFieldName").changeFirstString(handlerField.name)
            placeholder("lambdaBinderFieldName").changeFirstString(lambdaBinderField.name)
            placeholder("stateTweetFieldName").changeFirstString(stateTweetField.name)
            placeholder("stateItemFieldName").changeFirstString(stateItemField.name)
            placeholder("tweetFieldName").changeFirstString(tweetField.name)
            placeholder("hasFeedbackMethodName").changeFirstString(hasFeedback.name)
            placeholder("feedbackInfoMethodName").changeFirstString(feedbackInfo.name)
            placeholder("feedbackListFieldName").changeFirstString(feedbackList.name)
            placeholder("actionTypeFieldName").changeFirstString(actionType.name)
            placeholder("dismissMethodName").changeFirstString(dismiss.name)

            // Remember the item and its handler whenever a timeline post's menu gets a state.
            stateMethod.addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->onCurationState(Ljava/lang/Object;Ljava/lang/Object;)V",
            )

            enableSettings("notInterestedButton")
        }
    }
