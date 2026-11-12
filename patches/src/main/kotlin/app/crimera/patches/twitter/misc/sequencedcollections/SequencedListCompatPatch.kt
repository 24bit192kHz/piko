/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.sequencedcollections

import app.crimera.patches.twitter.misc.extension.sharedExtensionPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val COMPAT = "$PATCHES_DESCRIPTOR/compat/SequencedListCompat;"

// Lists that only got these methods in Android 15. Deque types (LinkedList, ArrayDeque) always had them.
private val LIST_TYPES = setOf(
    "Ljava/util/List;", "Ljava/util/ArrayList;", "Ljava/util/AbstractList;",
    "Ljava/util/Vector;", "Ljava/util/concurrent/CopyOnWriteArrayList;",
)

private val COMPAT_METHODS = mapOf(
    "removeFirst()Ljava/lang/Object;" to "removeFirst(Ljava/util/List;)Ljava/lang/Object;",
    "removeLast()Ljava/lang/Object;" to "removeLast(Ljava/util/List;)Ljava/lang/Object;",
    "getFirst()Ljava/lang/Object;" to "getFirst(Ljava/util/List;)Ljava/lang/Object;",
    "getLast()Ljava/lang/Object;" to "getLast(Ljava/util/List;)Ljava/lang/Object;",
    "addFirst(Ljava/lang/Object;)V" to "addFirst(Ljava/util/List;Ljava/lang/Object;)V",
    "addLast(Ljava/lang/Object;)V" to "addLast(Ljava/util/List;Ljava/lang/Object;)V",
)

@Suppress("unused")
val sequencedListCompatPatch =
    bytecodePatch(
        name = "Fix crashes on Android 14 and older",
        description = "X calls list methods that only exist on Android 15 and newer (removeFirst, getLast…). " +
            "On older Android this broke recording X Chat voice notes; the calls are replaced with ones that work everywhere.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(sharedExtensionPatch)

        execute {
            val targets = mutableListOf<Triple<String, String, Int>>()
            classDefForEach { classDef ->
                if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach
                classDef.methods.forEach { m ->
                    m.implementation?.instructions?.forEachIndexed { index, ins ->
                        if (ins.opcode != Opcode.INVOKE_VIRTUAL && ins.opcode != Opcode.INVOKE_INTERFACE) return@forEachIndexed
                        val ref = (ins as ReferenceInstruction).reference as MethodReference
                        val key = ref.name + "(" + ref.parameterTypes.joinToString("") + ")" + ref.returnType
                        if (ref.definingClass in LIST_TYPES && key in COMPAT_METHODS) {
                            targets += Triple(classDef.type, m.name + m.parameterTypes.joinToString("", "(", ")") + m.returnType, index)
                        }
                    }
                }
            }
            targets.groupBy { it.first }.forEach { (type, list) ->
                val cls = mutableClassDefBy(type)
                list.forEach { (_, sig, index) ->
                    val m = cls.methods.first { it.name + it.parameterTypes.joinToString("", "(", ")") + it.returnType == sig }
                    val ins = m.instructions[index] as FiveRegisterInstruction
                    val ref = (ins as ReferenceInstruction).reference as MethodReference
                    val key = ref.name + "(" + ref.parameterTypes.joinToString("") + ")" + ref.returnType
                    val regs = listOf(ins.registerC, ins.registerD).take(ins.registerCount).joinToString(", ") { "v$it" }
                    m.replaceInstruction(index, "invoke-static {$regs}, $COMPAT->${COMPAT_METHODS.getValue(key)}")
                }
            }
        }
    }
