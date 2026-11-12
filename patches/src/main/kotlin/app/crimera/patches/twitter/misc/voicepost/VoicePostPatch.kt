/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.voicepost

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/voicepost/VoicePost;"
private const val FOOTER = "Lcom/twitter/composer/view/ComposerFooterActionBar;"
private const val MEDIA_HELPER = "Lcom/twitter/composer/selfthread/ComposerExternalMediaHelper;"
private const val BRIDGE = "pikoAttachMedia"

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The composer's own code for media shared into it: the helper's method and the "source" it passes. */
private class Attach(val method: MethodReference, val source: FieldReference)

/** How the composer reads the id of the post being written (it is a thread of posts). */
private class CurrentPost(val manager: FieldReference, val post: FieldReference, val id: FieldReference)

@Suppress("unused")
val voicePostPatch =
    bytecodePatch(
        name = "Voice posts",
        description = "Adds a microphone button to the composer (and \"Record voice post\" to Piko settings): records your voice " +
            "and attaches it to the post as a short video (X for Android cannot make audio posts).",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch)

        execute {
            enableSettings("voicePost")

            // 1. The composer's footer bar: add the button once its buttons are inflated.
            val footer = mutableClassDefBy(FOOTER)
            var hooked = 0
            footer.methods.filter { it.name == "<init>" }.forEach { constructor ->
                val inflate = constructor.instructions.indexOfFirst {
                    it.opcode == Opcode.INVOKE_VIRTUAL && it.method()?.let { m ->
                        m.definingClass == "Landroid/view/LayoutInflater;" && m.name == "inflate"
                    } == true
                }
                if (inflate < 0) return@forEach
                val next = constructor.instructions[inflate + 1].opcode
                val index = if (next == Opcode.MOVE_RESULT_OBJECT) inflate + 2 else inflate + 1
                constructor.addInstruction(
                    index,
                    "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onComposerFooter(Landroid/view/View;)V",
                )
                hooked++
            }
            if (hooked == 0) throw PatchException("Composer footer bar does not inflate its buttons")

            // 2. The composer (the footer bar's listener, which owns the media helper): a bridge to attach
            //    a clip to the post being written, the way it attaches media shared from another app.
            val listeners = footer.fields.map { it.type }.filter { it.startsWith(FOOTER.dropLast(1) + "$") }.toSet()
            var found: ClassDef? = null
            classDefForEach { classDef ->
                if (classDef.interfaces.any { it in listeners } && classDef.fields.any { it.type == MEDIA_HELPER }) found = classDef
            }
            val composer = found ?: throw PatchException("Composer not found")
            val helper = composer.fields.first { it.type == MEDIA_HELPER }

            var attach: Attach? = null
            var current: CurrentPost? = null
            composer.methods.forEach { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                instructions.forEachIndexed { index, instruction ->
                    val before = instructions.subList(0, index)
                    if (attach == null && (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE)) {
                        // helper.attach(Uri, source, long postId, String altText)
                        val m = instruction.method()
                        val types = m?.parameterTypes?.map { it.toString() }
                        if (m != null && m.definingClass == MEDIA_HELPER && m.returnType == "V" && types!!.size == 4 &&
                            types[0] == "Landroid/net/Uri;" && types[2] == "J" && types[3] == "Ljava/lang/String;"
                        ) {
                            before.lastOrNull { it.opcode == Opcode.SGET_OBJECT && it.field()?.type == types[1] }
                                ?.let { attach = Attach(m, it.field()!!) }
                        }
                    }
                    if (current == null && instruction.opcode == Opcode.IPUT_WIDE && instruction.field()?.definingClass == MEDIA_HELPER) {
                        // helper.postId = this.manager.currentPost.id
                        val id = before.lastOrNull { it.opcode == Opcode.IGET_WIDE }?.field() ?: return@forEachIndexed
                        val post = before.lastOrNull { it.opcode == Opcode.IGET_OBJECT && it.field()?.type == id.definingClass }
                            ?.field() ?: return@forEachIndexed
                        val manager = before.lastOrNull {
                            it.opcode == Opcode.IGET_OBJECT && it.field()?.let { f ->
                                f.definingClass == composer.type && f.type == post.definingClass
                            } == true
                        }?.field() ?: return@forEachIndexed
                        current = CurrentPost(manager, post, id)
                    }
                }
            }
            val attachMedia = attach ?: throw PatchException("Composer does not attach shared media")
            val currentPost = current ?: throw PatchException("Composer's current post not found")

            val bridge = ImmutableMethod(
                composer.type,
                BRIDGE,
                listOf(ImmutableMethodParameter("Landroid/net/Uri;", emptySet(), null)),
                "Z",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.SYNTHETIC.value,
                emptySet(),
                emptySet(),
                MutableMethodImplementation(8),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                    iget-object v0, p0, ${currentPost.manager}
                    iget-object v0, v0, ${currentPost.post}
                    if-eqz v0, :none
                    iget-wide v3, v0, ${currentPost.id}
                    iget-object v0, p0, $helper
                    if-eqz v0, :none
                    move-object v1, p1
                    sget-object v2, ${attachMedia.source}
                    const/4 v5, 0x0
                    invoke-virtual/range {v0 .. v5}, ${attachMedia.method}
                    const/4 v0, 0x1
                    return v0
                    :none
                    const/4 v0, 0x0
                    return v0
                    """.trimIndent(),
                )
            }
            mutableClassDefBy(composer.type).methods.add(bridge)
        }
    }
