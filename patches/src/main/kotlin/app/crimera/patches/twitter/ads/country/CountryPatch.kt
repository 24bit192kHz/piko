/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 *
 * Feature after Control Panel for Twitter (https://github.com/insin/control-panel-for-twitter),
 * MIT License, Copyright (c) 2019 Jonny Buchanan.
 */

package app.crimera.patches.twitter.ads.country

import app.crimera.patches.twitter.ads.timelineEntryHook.hideSponsoredPostsPatch
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val FILTER_CLASS = "$PATCHES_DESCRIPTOR/country/CountryFilter;"
private const val LOOKUP_CLASS = "$PATCHES_DESCRIPTOR/country/CountryLookup;"

private const val USER_TO_STRING = "TwitterUser{userId="
private const val ABOUT_TO_STRING = "ProfileAboutMetadata(basedInCountry="

@Suppress("unused")
val countryFilterPatch =
    bytecodePatch(
        name = "Country filter",
        description = "Shows a flag after each author's name and hides posts from accounts based in countries you choose " +
            "(Control Panel for Twitter's feature). Countries come from X's own responses, X-Posed's shared cache, " +
            "and a small budget of X lookups while you use the app.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(hideSponsoredPostsPatch, settingsPatch)

        execute {
            val allClasses = mutableListOf<ClassDef>()
            classDefForEach { allClasses += it }

            // The user model (TwitterUser) and its "About this account" metadata.
            val userClass = classByToStringLabel(allClasses, USER_TO_STRING)
            // Two models print this label (legacy and com.x); the user model holds the legacy one.
            val aboutCandidates = allClasses.filter { classDef ->
                classDef.methods.any { m ->
                    m.name == "toString" && m.parameterTypes.isEmpty() &&
                        m.implementation?.instructions?.any { it.getReference<StringReference>()?.string == ABOUT_TO_STRING } == true
                }
            }.associateBy { it.type }
            val userAboutField = userClass.fields.singleOrNull { it.type in aboutCandidates }
                ?: throw PatchException("About metadata field not found in ${userClass.type}")
            val aboutClass = aboutCandidates.getValue(userAboutField.type)

            setPlaceholder(FILTER_CLASS, "userNameFieldName", toStringFieldName(userClass, ", username='"))
            setPlaceholder(FILTER_CLASS, "userAboutFieldName", userAboutField.name)
            setPlaceholder(FILTER_CLASS, "aboutCountryFieldName", toStringFieldName(aboutClass, ABOUT_TO_STRING))
            setPlaceholder(FILTER_CLASS, "aboutAccurateFieldName", toStringFieldName(aboutClass, ", isLocationAccurate="))

            // No hook in the user model's constructor: it made X Chat conversations bounce back to the
            // timeline, and timeline responses don't carry about_profile anyway (countries come from the
            // shared cache and X lookups).

            // X lookups go through the app's own com.x GraphQL client (AboutAccountQuery).
            val queryClass = allClasses.firstOrNull { classDef ->
                classDef.methods.any { m ->
                    m.name == "name" && m.parameterTypes.isEmpty() &&
                        m.implementation?.instructions?.any { it.getReference<StringReference>()?.string == "AboutProfileByScreenNameQuery" } == true
                }
            } ?: throw PatchException("AboutProfileByScreenNameQuery not found")
            val repository = allClasses.firstOrNull { classDef ->
                classDef.methods.any { m ->
                    m.implementation?.instructions?.any {
                        it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == queryClass.type
                    } == true
                } && classDef.methods.any { it.name == "<init>" && it.parameterTypes.size == 1 }
            } ?: throw PatchException("About repository not found")
            val graphqlInterface = repository.methods.first { it.name == "<init>" && it.parameterTypes.size == 1 }
                .parameterTypes.single().toString()
            // The real client lives next to its interface (feature modules wrap it elsewhere).
            val graphqlPackage = graphqlInterface.substringBeforeLast('/')
            val client = allClasses.singleOrNull { classDef ->
                graphqlInterface in classDef.interfaces && (classDef.accessFlags and AccessFlags.ABSTRACT.value) == 0 &&
                    classDef.type.substringBeforeLast('/') == graphqlPackage
            } ?: throw PatchException("GraphQL client for $graphqlInterface not found")

            setPlaceholder(LOOKUP_CLASS, "graphqlInterface", graphqlInterface.toJavaName())
            setPlaceholder(LOOKUP_CLASS, "aboutQueryClass", queryClass.type.toJavaName())
            setPlaceholder(LOOKUP_CLASS, "aboutRepositoryClass", repository.type.toJavaName())

            mutableClassDefBy(client.type).methods.filter { it.name == "<init>" }.forEach { ctor ->
                ctor.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.reversed().forEach { (index, _) ->
                    ctor.addInstruction(index, "invoke-static/range {p0 .. p0}, $LOOKUP_CLASS->onGraphqlClient(Ljava/lang/Object;)V")
                }
            }

            // Flags: the post header's name, at draw time (the user model and your own name stay untouched).
            val header = mutableClassDefBy("Lcom/twitter/ui/tweet/TweetHeaderView;").methods.singleOrNull {
                it.returnType == "V" && it.parameterTypes.map { p -> p.toString() } ==
                    listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/List;")
            } ?: throw PatchException("Post header name setter not found")
            header.addInstructions(
                0,
                """
                invoke-static/range {p1 .. p2}, $FILTER_CLASS->decorateName(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
                """,
            )

            enableSettings("countryFilter")
        }
    }

private fun String.toJavaName() = removePrefix("L").removeSuffix(";").replace('/', '.')

private fun classByToStringLabel(allClasses: List<ClassDef>, label: String): ClassDef =
    allClasses.singleOrNull { classDef ->
        classDef.methods.any { m ->
            m.name == "toString" && m.parameterTypes.isEmpty() &&
                m.implementation?.instructions?.any { it.getReference<StringReference>()?.string == label } == true
        }
    } ?: throw PatchException("Class with toString label \"$label\" not found")

/** The field a class's toString() prints right after [label]. */
private fun toStringFieldName(classDef: ClassDef, label: String): String {
    val instructions = classDef.methods.first { it.name == "toString" && it.parameterTypes.isEmpty() }
        .implementation!!.instructions.toList()
    val labelIndex = instructions.indexOfFirst { it.getReference<StringReference>()?.string == label }
    if (labelIndex < 0) throw PatchException("\"$label\" not in ${classDef.type}->toString()")
    return instructions.drop(labelIndex)
        .firstNotNullOfOrNull { ins -> ins.getReference<FieldReference>()?.takeIf { it.definingClass == classDef.type } }
        ?.name ?: throw PatchException("Field after \"$label\" not found in ${classDef.type}")
}

/** Replaces the string a placeholder method of an extension class returns. */
context(context: BytecodePatchContext)
private fun setPlaceholder(classType: String, methodName: String, value: String) {
    val method = context.mutableClassDefBy(classType).methods.firstOrNull { it.name == methodName }
        ?: throw PatchException("$classType->$methodName not found")
    val index = method.instructions.indexOfFirst { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
    if (index < 0) throw PatchException("$classType->$methodName has no string")
    val register = method.getInstruction<OneRegisterInstruction>(index).registerA
    method.replaceInstruction(index, "const-string v$register, \"$value\"")
}
