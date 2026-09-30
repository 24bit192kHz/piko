/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.ads.timelineEntryHook

import app.crimera.patches.twitter.entity.twitterUser.twitterUserEntity
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.crimera.utils.changeFirstString
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/SponsoredPosts;"

private object JsonApiTweetParseFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/api/model/json/core/JsonApiTweet\$\$JsonObjectMapper;",
    name = "parse",
    returnType = "Ljava/lang/Object",
)

private class ExtensionFieldNameFingerprint(
    methodName: String,
) : Fingerprint(
        definingClass = EXTENSION_CLASS,
        name = methodName,
    )

private const val JSON_MODEL = "Lcom/twitter/api/model/json"

/**
 * Extension placeholder method -> (LoganSquare mapper class, JSON key). The obfuscated field a
 * mapper's parseField() assigns for the key replaces the placeholder string.
 */
private val jsonFields =
    mapOf(
        "contentDisclosureField" to ("$JSON_MODEL/core/JsonApiTweet\$\$JsonObjectMapper;" to "content_disclosure"),
        "advertisingDisclosureField" to
            ("$JSON_MODEL/contentdisclosure/JsonContentDisclosure\$\$JsonObjectMapper;" to "advertising_disclosure"),
        "isPaidPromotionField" to
            ("$JSON_MODEL/contentdisclosure/JsonAdvertisingDisclosure\$\$JsonObjectMapper;" to "is_paid_promotion"),
        "coreField" to ("$JSON_MODEL/core/JsonApiTweet\$\$JsonObjectMapper;" to "core"),
        "userResultField" to ("$JSON_MODEL/core/JsonApiTweet\$JsonGraphQlTweetCore\$\$JsonObjectMapper;" to "user_result"),
        "userResultsField" to ("$JSON_MODEL/core/JsonApiTweet\$JsonGraphQlTweetCore\$\$JsonObjectMapper;" to "user_results"),
        "userResultsResultField" to ("Lcom/twitter/model/json/core/JsonUserResults\$\$JsonObjectMapper;" to "result"),
        "legacyField" to ("$JSON_MODEL/core/JsonApiTweet\$\$JsonObjectMapper;" to "legacy"),
        "fullTextField" to ("$JSON_MODEL/core/BaseJsonApiTweet\$\$JsonObjectMapper;" to "full_text"),
        "quotedResultField" to ("$JSON_MODEL/core/JsonApiTweet\$\$JsonObjectMapper;" to "quoted_status_result"),
        "retweetedResultField" to
            ("$JSON_MODEL/core/JsonApiTweet\$JsonGraphQlLegacyApiTweet\$\$JsonObjectMapper;" to "retweeted_status_result"),
        "noteTweetField" to ("$JSON_MODEL/core/JsonApiTweet\$\$JsonObjectMapper;" to "note_tweet"),
    )

/**
 * Extension placeholder method -> (label that starts a model class's toString(), label printed
 * right before the wanted field). Used for converted models that have no JSON mapper.
 */
private val toStringFields =
    mapOf(
        "noteTweetResultsField" to ("NoteTweetData(noteTweetResults=" to "NoteTweetData(noteTweetResults="),
        "noteTweetResultField" to ("NoteTweetResults(id=" to ", result="),
        "availableNoteTweetField" to ("NoteTweetAvailableResult(noteTweet=" to "NoteTweetAvailableResult(noteTweet="),
        "noteTweetTextField" to ("NoteTweet(id=" to ", text="),
    )

private class ToStringFingerprint(
    classLabel: String,
    fieldLabel: String,
) : Fingerprint(
        name = "toString",
        returnType = "Ljava/lang/String;",
        strings = listOf(classLabel, fieldLabel).distinct(),
    )

/** Name of the field a model's toString() prints right after [fieldLabel]. */
private fun BytecodePatchContext.toStringFieldName(
    classLabel: String,
    fieldLabel: String,
): String {
    val method = ToStringFingerprint(classLabel, fieldLabel).method
    val instructions = method.instructions.toList()
    val labelIndex = instructions.indexOfFirst { it.getReference<StringReference>()?.string == fieldLabel }
    return instructions
        .drop(labelIndex)
        .firstNotNullOfOrNull { ins -> ins.getReference<FieldReference>()?.takeIf { it.definingClass == method.definingClass } }
        ?.name
        ?: throw PatchException("Field after \"$fieldLabel\" not found in ${method.definingClass}->toString()")
}

/** Name of the field that [mapperClass]'s parseField() assigns when it reads [jsonKey]. */
private fun BytecodePatchContext.jsonFieldName(
    mapperClass: String,
    jsonKey: String,
): String {
    val mapper = classDefByOrNull(mapperClass) ?: throw PatchException("$mapperClass not found")
    mapper.methods.filter { it.name == "parseField" }.forEach { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@forEach
        val keyIndex = instructions.indexOfFirst { it.getReference<StringReference>()?.string == jsonKey }
        if (keyIndex < 0) return@forEach
        // The first model field after the key is the one assigned; the mapper's own static
        // fields (nested mappers it delegates to) are skipped.
        val field =
            instructions
                .drop(keyIndex)
                .firstNotNullOfOrNull { ins -> ins.getReference<FieldReference>()?.takeIf { it.definingClass != mapperClass } }
                ?: return@forEach
        return field.name
    }
    throw PatchException("Field for \"$jsonKey\" not found in $mapperClass")
}

@Suppress("unused")
val hideSponsoredPostsPatch =
    bytecodePatch(
        name = "Hide sponsored posts",
        description = "Hides paid partnership posts and posts labeled as ads, and adds an \"Allow ads from\" list of accounts whose ads stay visible.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(timelineEntryHookPatch, settingsPatch, twitterUserEntity)

        execute {
            jsonFields.forEach { (methodName, mapping) ->
                val (mapperClass, jsonKey) = mapping
                ExtensionFieldNameFingerprint(methodName).changeFirstString(jsonFieldName(mapperClass, jsonKey))
            }
            toStringFields.forEach { (methodName, labels) ->
                val (classLabel, fieldLabel) = labels
                ExtensionFieldNameFingerprint(methodName).changeFirstString(toStringFieldName(classLabel, fieldLabel))
            }

            // Report every parsed post, and its nesting depth, to the extension.
            JsonApiTweetParseFingerprint.method.apply {
                val returnIndex = instructions.last { it.opcode == Opcode.RETURN_OBJECT }.location.index
                val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                addInstructions(
                    returnIndex,
                    "invoke-static {v$returnRegister}, $EXTENSION_CLASS->onTweetParsed(Ljava/lang/Object;)V",
                )
                addInstruction(0, "invoke-static {}, $EXTENSION_CLASS->onTweetParseStart()V")
            }

            // Forget the previous entry's post when the next entry starts parsing.
            listOf(TimelineEntryHookFingerprint, TimelineModuleItemHookFingerprint).forEach {
                it.method.addInstruction(0, "invoke-static {}, $EXTENSION_CLASS->onEntryParseStart()V")
            }

            enableSettings("hideSponsoredPosts")
        }
    }
