/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.clientversion

import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// The app's own version name as compiled into the code ("12.19.1-release.0"),
// and the user agent fragment around it ("/12.19.1-release.0 (").
private val APP_VERSION_NAME = Regex("""^\d+\.\d+\.\d+-release\.\d+$""")
private val USER_AGENT_VERSION = Regex("""^/(\d+\.\d+\.\d+-release\.\d+) \($""")

private data class Target(
    val classType: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val index: Int,
    val replacement: String,
)

@Suppress("unused")
val spoofClientVersionPatch =
    bytecodePatch(
        name = "Spoof client version",
        description = "Reports a newer app version to X (version headers and user agent). " +
            "X Chat refuses old versions with \"Update Required\" (ClientRequiresUpdate).",
    ) {
        compatibleWith(COMPATIBILITY_X)

        val spoofedVersion by stringOption(
            key = "spoofedVersion",
            default = "12.41.0-prod.01",
            title = "Reported version name",
            description = "Version name sent to X instead of the app's own.",
            required = true,
        )

        val spoofedVersionCode by intOption(
            key = "spoofedVersionCode",
            default = 312410001,
            title = "Reported version code",
            description = "Version code sent in the user agent. It must belong to the reported version name.",
            required = true,
        )

        execute {
            val version = spoofedVersion!!
            val versionCode = spoofedVersionCode!!
            val targets = mutableListOf<Target>()
            val found = mutableSetOf<String>()
            var userAgents = 0

            classDefForEach { classDef ->
                classDef.methods.forEach { method ->
                    val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                    instructions.forEachIndexed { index, instruction ->
                        if (instruction.opcode != Opcode.CONST_STRING &&
                            instruction.opcode != Opcode.CONST_STRING_JUMBO
                        ) {
                            return@forEachIndexed
                        }
                        val value = ((instruction as ReferenceInstruction).reference as StringReference).string
                        fun add(at: Int, replacement: String) = targets.add(
                            Target(
                                classDef.type,
                                method.name,
                                method.parameterTypes.map { it.toString() },
                                method.returnType,
                                at,
                                replacement,
                            ),
                        )

                        if (APP_VERSION_NAME.matches(value)) {
                            found += value
                            add(index, "const-string/jumbo v%d, \"$version\"")
                            return@forEachIndexed
                        }

                        val userAgent = USER_AGENT_VERSION.matchEntire(value) ?: return@forEachIndexed
                        found += userAgent.groupValues[1]
                        add(index, "const-string/jumbo v%d, \"/$version (\"")
                        // The user agent appends PackageInfo.versionCode right after; report the matching code.
                        val versionCodeIndex = (index + 1 until minOf(index + 12, instructions.size)).firstOrNull {
                            val field = (instructions[it] as? ReferenceInstruction)?.reference as? FieldReference
                            instructions[it].opcode == Opcode.IGET &&
                                field?.definingClass == "Landroid/content/pm/PackageInfo;" &&
                                field.name == "versionCode"
                        } ?: throw PatchException("User agent version code not found in ${classDef.type}->${method.name}")
                        add(versionCodeIndex, "const v%d, $versionCode")
                        userAgents++
                    }
                }
            }

            if (found.size != 1) throw PatchException("Expected one app version, found $found")
            if (userAgents != 1) throw PatchException("Expected one user agent builder, found $userAgents")

            targets.groupBy { it.classType }.forEach { (type, classTargets) ->
                val mutableClass = mutableClassDefBy(type)
                classTargets.forEach { target ->
                    val method = mutableClass.methods.first {
                        it.name == target.methodName &&
                            it.returnType == target.returnType &&
                            it.parameterTypes.map { p -> p.toString() } == target.parameterTypes
                    }
                    val instruction = method.getInstruction(target.index)
                    val register = when (instruction) {
                        is OneRegisterInstruction -> instruction.registerA
                        else -> (instruction as TwoRegisterInstruction).registerA
                    }
                    method.replaceInstruction(target.index, target.replacement.format(register))
                }
            }
        }
    }
