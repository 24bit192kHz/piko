/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.mutetext

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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/MutedWords;"
private const val USER_IDENTIFIER = "Lcom/twitter/util/user/UserIdentifier;"
private const val REQUEST_CONTROLLER = "Lcom/twitter/async/http/g;"

private object ApplicationOnCreateFingerprint : Fingerprint(
    superclass = "Landroid/app/Application;",
    name = "onCreate",
)

/** X's "create muted word" request (POST /1.1/mutes/keywords/create.json). */
private object CreateMutedWordFingerprint : Fingerprint(
    strings = listOf("/1.1/mutes/keywords/create.json"),
)

private object DestroyMutedWordFingerprint : Fingerprint(
    strings = listOf("/1.1/mutes/keywords/destroy.json"),
)

/**
 * Where a muted word applies: none, notifications, home_timeline, tweet_replies. The legacy model's
 * enum prints its API name (toString); com.x.models.MuteSurfaceType has the same strings but prints
 * HOME_TIMELINE, which X refuses.
 */
private object MuteSurfaceFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("home_timeline", "tweet_replies", "notifications"),
    custom = { _, classDef ->
        classDef.superclass == "Ljava/lang/Enum;" &&
            classDef.methods.any { it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
    },
)

private fun placeholder(name: String) = object : Fingerprint(definingClass = EXTENSION_CLASS, name = name) {}

private fun javaName(type: String) = type.removePrefix("L").removeSuffix(";").replace('/', '.')

@Suppress("unused")
val muteSelectedTextPatch =
    patch(
        name = "Mute selected text",
        description = "Adds \"Mute\" to the text selection menu: posts with the selected words are hidden at once " +
            "(also posts already loaded), the words go on X's muted words too, and the post closes.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch, inlineDownloadButtonPatch)

        execute {
            val createRequest = CreateMutedWordFingerprint.method.definingClass
            val destroyRequest = DestroyMutedWordFingerprint.method.definingClass
            val createClass = classDefByOrNull(createRequest) ?: throw PatchException("$createRequest not found")
            // i(UserIdentifier, String errorMessage, <muted word>, long duration)
            val createInit = createClass.methods.firstOrNull {
                it.name == "<init>" && it.parameterTypes.size == 4 && it.parameterTypes[0] == USER_IDENTIFIER &&
                    it.parameterTypes[1] == "Ljava/lang/String;" && it.parameterTypes[3] == "J"
            } ?: throw PatchException("Muted word request constructor not found")
            val keywordModel = createInit.parameterTypes[2].toString()
            val modelClass = classDefByOrNull(keywordModel) ?: throw PatchException("$keywordModel not found")
            val modelInit = modelClass.methods.firstOrNull {
                it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } ==
                    listOf("J", "Ljava/lang/String;", "Ljava/lang/String;", "J", "J", "Ljava/util/Set;", "Ljava/util/Set;")
            } ?: throw PatchException("Muted word model constructor not found")
            if (modelInit.instructions.count { it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>()?.type == "Ljava/lang/String;" } < 2) {
                throw PatchException("Muted word id and keyword fields not found")
            }
            val destroyClass = classDefByOrNull(destroyRequest) ?: throw PatchException("$destroyRequest not found")
            if (destroyClass.methods.none { it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == listOf(USER_IDENTIFIER, "Ljava/lang/String;", "[Ljava/lang/String;") }) {
                throw PatchException("Muted word delete request constructor not found")
            }
            // The request's result: X's list of muted words (a model with one ArrayList field).
            val base = classDefByOrNull(createClass.superclass!!) ?: throw PatchException("Muted word request base not found")
            val resultField = base.fields.firstOrNull { f ->
                !AccessFlags.STATIC.isSet(f.accessFlags) && f.type.startsWith("L") &&
                    classDefByOrNull(f.type)?.fields?.let { fs -> fs.count() == 1 && fs.first().type == "Ljava/util/ArrayList;" } == true
            } ?: throw PatchException("Muted word request result field not found")
            // ...or X's error (a model with one String field: the message).
            val errorField = base.fields.firstOrNull { f ->
                !AccessFlags.STATIC.isSet(f.accessFlags) && f.type.startsWith("L") && f.type != resultField.type &&
                    classDefByOrNull(f.type)?.fields?.let { fs -> fs.count() == 1 && fs.first().type == "Ljava/lang/String;" } == true
            } ?: throw PatchException("Muted word request error field not found")
            val surfaceEnum = MuteSurfaceFingerprint.method.definingClass
            val controller = classDefByOrNull(REQUEST_CONTROLLER) ?: throw PatchException("$REQUEST_CONTROLLER not found")
            if (controller.methods.none { it.name == "d" && it.parameterTypes.isEmpty() && it.returnType == REQUEST_CONTROLLER && AccessFlags.STATIC.isSet(it.accessFlags) }) {
                throw PatchException("Request controller instance method not found")
            }
            if (classDefByOrNull(USER_IDENTIFIER)?.methods?.none { it.name == "getCurrent" } != false) {
                throw PatchException("UserIdentifier.getCurrent not found")
            }

            placeholder("createRequestClass").changeFirstString(javaName(createRequest))
            placeholder("destroyRequestClass").changeFirstString(javaName(destroyRequest))
            placeholder("keywordModelClass").changeFirstString(javaName(keywordModel))
            placeholder("surfaceEnumClass").changeFirstString(javaName(surfaceEnum))
            placeholder("requestResultFieldName").changeFirstString(resultField.name)
            placeholder("requestErrorFieldName").changeFirstString(errorField.name)
            placeholder("dispatcherClass").changeFirstString(javaName(REQUEST_CONTROLLER))

            ApplicationOnCreateFingerprint.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->init(Landroid/app/Application;)V",
            )

            enableSettings("muteSelection")
        }
    }
