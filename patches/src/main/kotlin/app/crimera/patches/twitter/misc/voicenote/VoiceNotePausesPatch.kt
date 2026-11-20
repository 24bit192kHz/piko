/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.voicenote

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "$PATCHES_DESCRIPTOR/voice/VoiceNotes;"
private const val WEIGHTS_ASSET = "assets/piko/silero_vad_v6.bin"

/** Silero VAD v6 weights (MIT, Silero Team) for the pause detection, as an asset. */
private val voiceNoteModelPatch =
    resourcePatch {
        execute {
            val target = get(WEIGHTS_ASSET, copy = false)
            target.parentFile.mkdirs()
            val stream = object {}.javaClass.classLoader.getResourceAsStream("twitter/voice/silero_vad_v6.bin")
                ?: throw PatchException("Silero VAD weights missing from the patches")
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            // Noise removal (DeepFilterNet3): libpikodf.so (libDF DSP, model inside) on ONNX Runtime's official
            // Android build, both made by native/pikodf/build-android.sh (not in git). arm64 only, like this X
            // build; X loads its libraries straight from the APK (extractNativeLibs=false), so the APK must keep
            // them stored uncompressed. Without them the pauses are still cut, only the noise stays.
            val loader = object {}.javaClass.classLoader
            val libs = listOf("libonnxruntime.so", "libpikodf.so")
            if (libs.all { loader.getResource("twitter/voice/arm64-v8a/$it") != null }) {
                for (name in libs) {
                    val lib = get("lib/arm64-v8a/$name", copy = false)
                    lib.parentFile.mkdirs()
                    loader.getResourceAsStream("twitter/voice/arm64-v8a/$name")!!.use { input -> lib.outputStream().use { input.copyTo(it) } }
                }
            } else {
                java.util.logging.Logger.getLogger("VoiceNotePausesPatch")
                    .warning("Noise removal libraries not built (native/pikodf/build-android.sh): voice notes keep their noise")
            }
        }
    }

private fun Method.strings() = implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string }.orEmpty()

private fun String.javaName() = removePrefix("L").removeSuffix(";").replace('/', '.')

@Suppress("unused")
val voiceNotePausesPatch =
    bytecodePatch(
        name = "Voice notes without pauses",
        description = "Cuts the pauses out of the X Chat voice notes you send (like clearcut: Silero VAD + jumpcutter's " +
            "planner, on the phone), and makes the time of a voice note a button that plays it without its pauses.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch, voiceNoteModelPatch)

        execute {
            val all = mutableListOf<ClassDef>()
            classDefForEach { all += it }

            // 1. Sent: X Chat's voice note encoder hands over (file uri, duration, file size) when recording stops.
            val encoder = all.firstOrNull { c -> c.methods.any { "Could not signal EOS after 100 retries" in it.strings() } }
                ?: throw PatchException("Voice note encoder not found")
            val finish = mutableClassDefBy(encoder.type).methods.single {
                it.parameterTypes.isEmpty() && it.returnType.startsWith("L") && it.returnType != "Ljava/lang/Object;" &&
                    it.implementation?.instructions?.any { i -> i.opcode == Opcode.RETURN_OBJECT } == true &&
                    "Could not signal EOS after 100 retries" in it.strings()
            }
            finish.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.reversed().forEach { (index, ins) ->
                val r = (ins as OneRegisterInstruction).registerA
                finish.addInstructions(
                    index,
                    """
                    invoke-static/range {v$r .. v$r}, $EXTENSION->onRecorded(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$r
                    check-cast v$r, ${finish.returnType}
                    """,
                )
            }

            // 2. The recorded note (uri, duration, levels, size) that the preview and the upload use: re-timed levels.
            val recorded = all.firstOrNull { c ->
                c.methods.any {
                    it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } ==
                        listOf("Ljava/lang/String;", "Lcom/x/export/KmpDuration;", "Ljava/util/List;", "Lcom/x/models/media/FileSize;")
                }
            } ?: throw PatchException("Recorded voice note model not found")
            mutableClassDefBy(recorded.type).methods.single {
                it.name == "<init>" && it.parameterTypes.size == 4 && it.parameterTypes[2].toString() == "Ljava/util/List;"
            }.addInstructions(
                0,
                """
                invoke-static/range {p1 .. p3}, $EXTENSION->amplitudes(Ljava/lang/String;Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;
                move-result-object p3
                """,
            )

            // 3. Heard: X Chat's voice note player opens a file with MediaPlayer.setDataSource(uri).
            val player = all.firstOrNull { c -> c.methods.any { "Failed to prepare player for " in it.strings() } }
                ?: throw PatchException("Voice note player not found")
            val playerDef = mutableClassDefBy(player.type)
            val play = playerDef.methods.single { m ->
                m.implementation?.instructions?.any {
                    it.getReference<MethodReference>()?.let { r -> r.definingClass == "Landroid/media/MediaPlayer;" && r.name == "setDataSource" } == true
                } == true
            }
            val setSource = play.instructions.indexOfFirst {
                it.getReference<MethodReference>()?.let { r -> r.definingClass == "Landroid/media/MediaPlayer;" && r.name == "setDataSource" } == true
            }
            val src = play.getInstruction<FiveRegisterInstruction>(setSource)
            play.replaceInstruction(
                setSource,
                "invoke-static {v${src.registerC}, v${src.registerD}}, $EXTENSION->setDataSource(Landroid/media/MediaPlayer;Ljava/lang/String;)V",
            )
            val uriField = playerDef.fields.singleOrNull { it.type == "Ljava/lang/String;" && !AccessFlags.STATIC.isSet(it.accessFlags) }
                ?: throw PatchException("Voice note player's current uri not found")
            // While a note plays without pauses, X's line and taps (0..1 of the original) map onto the copy.
            playerDef.methods.filter { it.returnType == "F" && it.parameterTypes.isEmpty() }.forEach { progress ->
                progress.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }.reversed().forEach { (i, r) ->
                    val reg = (r as OneRegisterInstruction).registerA
                    progress.addInstructions(
                        i,
                        """
                        invoke-static {v$reg}, $EXTENSION->progressOut(F)F
                        move-result v$reg
                        """,
                    )
                }
            }
            playerDef.methods.filter { it.returnType == "V" && it.parameterTypes.map { t -> t.toString() } == listOf("F") }.forEach { seek ->
                seek.addInstructions(
                    0,
                    """
                    invoke-static {p1}, $EXTENSION->seekIn(F)F
                    move-result p1
                    """,
                )
            }
            // X's StateFlow factory, as the player makes its own state (a change signal for the notes).
            val flowFactory = playerDef.methods.first { it.name == "<init>" }.instructions.firstNotNullOfOrNull {
                it.getReference<MethodReference>()?.takeIf { r ->
                    it.opcode == Opcode.INVOKE_STATIC && r.parameterTypes.map { t -> t.toString() } == listOf("Ljava/lang/Object;") &&
                        r.definingClass.startsWith("Lkotlinx/coroutines/flow/")
                }
            } ?: throw PatchException("StateFlow factory not found")
            setPlaceholder(EXTENSION, "flowClassName", flowFactory.definingClass.javaName())
            setPlaceholder(EXTENSION, "flowMethodName", flowFactory.name)
            setPlaceholder(EXTENSION, "playMethodName", play.name)
            setPlaceholder(EXTENSION, "playerUriFieldName", uriField.name)

            // 4. The voice note bubble: its time label is Text(format(remaining), modifier = null, …).
            val bubbleClass = all.firstOrNull { c ->
                c.methods.any { m -> m.strings().let { "playbackController" in it && "attachment" in it } && m.parameterTypes.size > 8 }
            } ?: throw PatchException("Voice note bubble not found")
            val bubble = mutableClassDefBy(bubbleClass.type).methods.first { m ->
                m.strings().let { "playbackController" in it && "attachment" in it } && m.parameterTypes.size > 8
            }
            val attachmentType = bubble.parameterTypes[0].toString()
            val ins = bubble.instructions.toList()
            val uriCall = ins.firstNotNullOfOrNull { i ->
                i.getReference<MethodReference>()?.takeIf {
                    i.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == attachmentType && it.parameterTypes.isEmpty() &&
                        it.returnType == "Ljava/lang/String;"
                }
            } ?: throw PatchException("Voice note uri getter not found")
            setPlaceholder(EXTENSION, "attachmentUriMethodName", uriCall.name)
            val formatAt = ins.indexOfFirst { i ->
                i.opcode == Opcode.INVOKE_STATIC && i.getReference<MethodReference>()?.let {
                    it.parameterTypes.map { p -> p.toString() } == listOf("J") && it.returnType == "Ljava/lang/String;"
                } == true
            }
            if (formatAt < 0 || ins[formatAt + 1].opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("Voice note time label not found")
            val format = ins[formatAt].getReference<MethodReference>()!!
            setPlaceholder(EXTENSION, "labelFormatClassName", format.definingClass.javaName())
            setPlaceholder(EXTENSION, "labelFormatMethodName", format.name)
            val text = (ins[formatAt + 1] as OneRegisterInstruction).registerA
            val textCallAt = (formatAt + 2 until ins.size).firstOrNull { i ->
                ins[i].opcode == Opcode.INVOKE_STATIC_RANGE && ins[i].getReference<MethodReference>()?.let {
                    val p = it.parameterTypes.map { t -> t.toString() }
                    p.size > 4 && p[0] == "Ljava/lang/String;" && p[1] == "Landroidx/compose/ui/Modifier;" &&
                        p.takeLast(4) == listOf("Landroidx/compose/runtime/Composer;", "I", "I", "I")
                } == true && (ins[i] as RegisterRangeInstruction).startRegister == text
            } ?: throw PatchException("Voice note time Text not found")
            val range = ins[textCallAt] as RegisterRangeInstruction
            val modifierReg = range.startRegister + 1
            val maskReg = range.startRegister + range.registerCount - 1
            // X's building blocks the bubble itself uses: its Text, Icon (com.x.icons), size, padding,
            // background, collectAsState; the pill shape comes from Compose's RoundedCornerShape factory.
            val textRef = ins[textCallAt].getReference<MethodReference>()!!
            setPlaceholder(EXTENSION, "textClassName", textRef.definingClass.javaName())
            setPlaceholder(EXTENSION, "textMethodName", textRef.name)
            fun staticCall(from: Int = 0, pred: (MethodReference) -> Boolean) = (from until ins.size).firstOrNull { i ->
                (ins[i].opcode == Opcode.INVOKE_STATIC || ins[i].opcode == Opcode.INVOKE_STATIC_RANGE) &&
                    ins[i].getReference<MethodReference>()?.let(pred) == true
            }
            fun MethodReference.params() = parameterTypes.map { it.toString() }
            val iconAt = staticCall { it.params().firstOrNull()?.startsWith("Lcom/x/icons/") == true && it.returnType == "V" }
                ?: throw PatchException("X icon composable not found")
            val iconRef = ins[iconAt].getReference<MethodReference>()!!
            setPlaceholder(EXTENSION, "iconComposableClassName", iconRef.definingClass.javaName())
            setPlaceholder(EXTENSION, "iconComposableMethodName", iconRef.name)
            setPlaceholder(EXTENSION, "iconTypeClassName", iconRef.params()[0].javaName())
            val modifierType = "Landroidx/compose/ui/Modifier;"
            val sizeRef = (iconAt downTo 0).firstNotNullOfOrNull { i ->
                ins[i].getReference<MethodReference>()?.takeIf {
                    ins[i].opcode == Opcode.INVOKE_STATIC && it.params() == listOf(modifierType, "F") && it.returnType == modifierType
                }
            } ?: throw PatchException("Compose size modifier not found")
            setPlaceholder(EXTENSION, "sizeClassName", sizeRef.definingClass.javaName())
            setPlaceholder(EXTENSION, "sizeMethodName", sizeRef.name)
            val paddingRef = staticCall { it.params() == listOf(modifierType, "F", "F") && it.returnType == modifierType }
                ?.let { ins[it].getReference<MethodReference>()!! } ?: throw PatchException("Compose padding modifier not found")
            setPlaceholder(EXTENSION, "paddingClassName", paddingRef.definingClass.javaName())
            setPlaceholder(EXTENSION, "paddingMethodName", paddingRef.name)
            val backgroundRef = staticCall {
                it.params().size == 3 && it.params()[0] == modifierType && it.params()[1] == "J" && it.returnType == modifierType &&
                    it.definingClass.startsWith("Landroidx/compose/foundation/")
            }?.let { ins[it].getReference<MethodReference>()!! } ?: throw PatchException("Compose background modifier not found")
            setPlaceholder(EXTENSION, "backgroundClassName", backgroundRef.definingClass.javaName())
            setPlaceholder(EXTENSION, "backgroundMethodName", backgroundRef.name)
            val collectRef = staticCall {
                it.params().size == 3 && it.params()[0].startsWith("Lkotlinx/coroutines/flow/") && it.params()[1] == "Landroidx/compose/runtime/Composer;"
            }?.let { ins[it].getReference<MethodReference>()!! } ?: throw PatchException("collectAsState not found")
            setPlaceholder(EXTENSION, "collectClassName", collectRef.definingClass.javaName())
            setPlaceholder(EXTENSION, "collectMethodName", collectRef.name)
            val shapeFactory = all.filter { it.type.startsWith("Landroidx/compose/foundation/shape/") }.firstNotNullOfOrNull { c ->
                val byPercent = c.methods.firstOrNull { m -> AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.map { it.toString() } == listOf("I") }
                val byDp = c.methods.firstOrNull { m -> AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.map { it.toString() } == listOf("F") }
                val four = c.methods.firstOrNull { m -> AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.map { it.toString() } == listOf("F", "F", "F", "F") }
                byPercent?.takeIf { byDp != null && four != null && byDp.returnType == it.returnType && four.returnType == it.returnType }
            } ?: throw PatchException("RoundedCornerShape factory not found")
            setPlaceholder(EXTENSION, "shapeClassName", shapeFactory.definingClass.javaName())
            setPlaceholder(EXTENSION, "shapeMethodName", shapeFactory.name)

            // The controls go in just before X's time Text, with that Text's own arguments (same registers).
            val controlsDescriptor = textRef.params().joinToString("") {
                if (it == "Ljava/lang/String;" || it.length == 1) it else "Ljava/lang/Object;"
            }
            val expected = "Ljava/lang/String;Ljava/lang/Object;JLjava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;" +
                "Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;JIZIILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;III"
            if (controlsDescriptor != expected) throw PatchException("Voice note time Text changed: $controlsDescriptor")
            // X's time Text becomes the time with the voice note's controls under it (same arguments, same registers).
            bubble.replaceInstruction(
                textCallAt,
                "invoke-static/range {v${range.startRegister} .. v${range.startRegister + range.registerCount - 1}}, " +
                    "$EXTENSION->timeColumn($controlsDescriptor)V",
            )
            // The layout primitives the extension calls (as X 12.19.1's compiled Column/Row use them) must exist.
            val needed = mapOf(
                "Landroidx/compose/runtime/Composer;" to listOf("c", "d", "k", "A", "W", "f", "h"),
                "Landroidx/compose/runtime/e6;" to listOf("b", "c"),
                "Landroidx/compose/ui/g;" to listOf("c"),
                "Landroidx/compose/foundation/layout/d0;" to listOf("a"),
                "Landroidx/compose/foundation/layout/o4;" to listOf("a"),
            )
            needed.forEach { (type, names) ->
                val c = all.firstOrNull { it.type == type } ?: throw PatchException("$type not found")
                names.forEach { n -> if (c.methods.none { it.name == n }) throw PatchException("$type->$n not found") }
            }
            mapOf(
                "Landroidx/compose/ui/node/g\$a;" to listOf("b", "g", "f", "j", "k", "d"),
                "Landroidx/compose/foundation/layout/h;" to listOf("a", "c"),
                "Landroidx/compose/ui/b\$a;" to listOf("l", "p"),
            ).forEach { (type, names) ->
                val c = all.firstOrNull { it.type == type } ?: throw PatchException("$type not found")
                names.forEach { n -> if (c.fields.none { it.name == n }) throw PatchException("$type.$n not found") }
            }
            bubble.addInstructions(
                formatAt + 2,
                """
                invoke-static/range {p0 .. p2}, $EXTENSION->labelContext(Ljava/lang/Object;ZLjava/lang/Object;)V
                invoke-static/range {v$text .. v$text}, $EXTENSION->label(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$text
                """,
            )

            // X's own Modifier.clickable(enabled, onClickLabel, role, interactionSource, onClick, $default).
            val clickable = all.asSequence().filter { it.type.startsWith("Landroidx/compose/foundation/") }.flatMap { it.methods.asSequence() }
                .firstOrNull { m ->
                    AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "Landroidx/compose/ui/Modifier;" &&
                        m.parameterTypes.map { it.toString() }.let { p ->
                            p.size == 7 && p[0] == "Landroidx/compose/ui/Modifier;" && p[1] == "Z" && p[2] == "Ljava/lang/String;" &&
                                p[5] == "Lkotlin/jvm/functions/Function0;" && p[6] == "I" &&
                                p[3].startsWith("Landroidx/compose/ui/semantics/") && p[4].startsWith("Landroidx/compose/foundation/interaction/")
                        }
                } ?: throw PatchException("Compose clickable not found")
            setPlaceholder(EXTENSION, "clickableClassName", clickable.definingClass.javaName())
            setPlaceholder(EXTENSION, "clickableMethodName", clickable.name)

            enableSettings("voiceNotePauses")
        }
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
