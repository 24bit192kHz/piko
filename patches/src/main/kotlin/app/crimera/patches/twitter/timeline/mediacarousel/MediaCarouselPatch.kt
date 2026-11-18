/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.mediacarousel

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.crimera.utils.changeFirstString
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/MediaCarousel;"
private const val MEDIA_VIEW = "Lcom/twitter/media/ui/image/TweetMediaView;"
private const val SIZE = "Lcom/twitter/util/math/j;"

private fun placeholder(name: String) = object : Fingerprint(definingClass = EXTENSION_CLASS, name = name) {}

/** The media view's measure step: it measures its items for a size and returns that size. */
private object MeasureItemsFingerprint : Fingerprint(
    definingClass = MEDIA_VIEW,
    returnType = SIZE,
    parameters = listOf("I", "I"),
)

private object SetMediaItemsFingerprint : Fingerprint(
    definingClass = MEDIA_VIEW,
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    strings = listOf("Too many media items"),
)

private object OnLayoutFingerprint : Fingerprint(
    definingClass = MEDIA_VIEW,
    name = "onLayout",
    returnType = "V",
    parameters = listOf("Z", "I", "I", "I", "I"),
)

@Suppress("unused")
val mediaCarouselPatch =
    patch(
        name = "Show all media of a post",
        description = "Posts with more than 4 photos or videos (up to 20) show all of them at once, uncropped, in rows " +
            "that fit the post (optionally posts with 2 to 4 items too).",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch)

        execute {
            val view = classDefByOrNull(MEDIA_VIEW) ?: throw PatchException("$MEDIA_VIEW not found")
            val slotsField = view.fields.singleOrNull { it.type == "[Landroid/view/ViewGroup;" }
                ?: throw PatchException("Media slots field not found")
            val slotLayout = view.methods.singleOrNull {
                it.returnType == "V" && it.parameterTypes.map { p -> p.toString() } ==
                    listOf("Landroid/view/ViewGroup;", "I", "I", "I", "I")
            } ?: throw PatchException("Slot layout method not found")
            // 1. Room for 20 items: the item count cap and the slot arrays (4) follow the extension.
            SetMediaItemsFingerprint.method.apply {
                val minIndex = instructions.indexOfFirst {
                    it.opcode == Opcode.INVOKE_STATIC && it.getReference<MethodReference>()?.let { m ->
                        m.definingClass == "Ljava/lang/Math;" && m.name == "min"
                    } == true
                }
                val constIndex = (minIndex - 1 downTo 0).first { getInstruction(it).opcode == Opcode.CONST_4 }
                val register = getInstruction<OneRegisterInstruction>(constIndex).registerA
                addInstructions(
                    constIndex + 1,
                    """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->maxItems(I)I
                    move-result v$register
                    """,
                )
                // The items the view keeps: the list field it stores the sublist in.
                val itemsField = instructions.firstNotNullOfOrNull {
                    if (it.opcode == Opcode.IPUT_OBJECT) it.getReference<FieldReference>()?.takeIf { f ->
                        f.definingClass == MEDIA_VIEW && f.type == "Ljava/util/List;"
                    } else null
                } ?: throw PatchException("Media items field not found")
                placeholder("itemsFieldName").changeFirstString(itemsField.name)
            }
            mutableClassDefBy(MEDIA_VIEW).methods.forEach { method ->
                if (method.implementation == null) return@forEach
                val list = method.instructions.toList()
                list.withIndex().reversed().forEach { (index, instruction) ->
                    if (instruction.opcode != Opcode.NEW_ARRAY) return@forEach
                    val type = instruction.getReference<TypeReference>()?.type ?: return@forEach
                    if (type != "[Landroid/view/ViewGroup;" && !type.startsWith("[Lcom/twitter/media/ui/image/")) return@forEach
                    val sizeRegister = (instruction as TwoRegisterInstruction).registerB
                    val previous = list.getOrNull(index - 1) ?: return@forEach
                    if (previous.opcode != Opcode.CONST_4 || (previous as OneRegisterInstruction).registerA != sizeRegister) return@forEach
                    if ((previous as NarrowLiteralInstruction).narrowLiteral != 4) return@forEach
                    method.replaceInstruction(index - 1, "const/16 v$sizeRegister, 0x14")
                }
            }

            // 2. Measure: after the grid measured its items, arranged items are measured again at their boxes.
            MeasureItemsFingerprint.method.apply {
                val sizeIndex = instructions.indexOfLast {
                    it.opcode == Opcode.INVOKE_STATIC && it.getReference<MethodReference>()?.returnType == SIZE
                }
                if (sizeIndex < 0) throw PatchException("Measured size creation not found")
                val scratch = (getInstruction(sizeIndex - 2) as? OneRegisterInstruction)?.registerA
                    ?: throw PatchException("No scratch register before the size")
                addInstructionsWithLabels(
                    sizeIndex,
                    """
                    invoke-static/range { p0 .. p2 }, $EXTENSION_CLASS->measure(Landroid/view/ViewGroup;II)I
                    move-result v$scratch
                    if-ltz v$scratch, :piko_keep_grid
                    move p2, v$scratch
                    """,
                    ExternalLabel("piko_keep_grid", getInstruction(sizeIndex)),
                )
            }

            // 3. Layout: where the grid layout ends (a jump target), arranged items go to their boxes.
            OnLayoutFingerprint.method.apply {
                val badgeIndex = instructions.indexOfFirst {
                    it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.let { f ->
                        f.definingClass == MEDIA_VIEW && f.type == "Lcom/twitter/media/ui/image/FixedSizeImageView;"
                    } == true
                }
                if (badgeIndex < 0) throw PatchException("End of the grid layout not found")
                val original = getInstruction(badgeIndex)
                val field = original.getReference<FieldReference>()!!
                val two = original as TwoRegisterInstruction
                // Replacing keeps the jumps to this instruction on the hook; the original read follows it.
                replaceInstruction(badgeIndex, "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->layout(Landroid/view/ViewGroup;)V")
                addInstruction(badgeIndex + 1, "iget-object v${two.registerA}, v${two.registerB}, $field")
            }

            // 4. Names the extension reads.
            placeholder("slotsFieldName").changeFirstString(slotsField.name)
            placeholder("layoutSlotMethodName").changeFirstString(slotLayout.name)
            val itemClass = SetMediaItemsFingerprint.method.instructions.firstNotNullOfOrNull {
                if (it.opcode == Opcode.CHECK_CAST) it.getReference<TypeReference>()?.type?.takeIf { t ->
                    t.startsWith("Lcom/twitter/media/ui/image/") && t != MEDIA_VIEW && !t.contains("Fresco")
                } else null
            } ?: throw PatchException("Media item class not found")
            val itemObject = (classDefByOrNull(itemClass) ?: throw PatchException("$itemClass not found"))
                .fields.firstOrNull { it.type == "Ljava/lang/Object;" } ?: throw PatchException("Media item object field not found")
            placeholder("itemObjectFieldName").changeFirstString(itemObject.name)
            val entity = classDefByOrNull("Lcom/twitter/model/core/entity/c0;") ?: throw PatchException("Media entity not found")
            val sizeField = entity.fields.singleOrNull { it.type == SIZE } ?: throw PatchException("Media entity size not found")
            placeholder("sizeFieldName").changeFirstString(sizeField.name)
            val sizeInts = (classDefByOrNull(SIZE) ?: throw PatchException("$SIZE not found"))
                .fields.filter { it.type == "I" && (it.accessFlags and AccessFlags.STATIC.value) == 0 }.toList()
            if (sizeInts.size < 2) throw PatchException("Size fields not found")
            placeholder("sizeWidthFieldName").changeFirstString(sizeInts[0].name)
            placeholder("sizeHeightFieldName").changeFirstString(sizeInts[1].name)

            enableSettings("mediaCarousel")
        }
    }
