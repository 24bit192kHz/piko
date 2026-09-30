/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.screenshot

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PREF_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

private const val DETECTOR_PACKAGE = "Lcom/x/android/screenshot/detector/"

// Android 14+: Activity.ScreenCaptureCallback.
private object ScreenCapturedFingerprint : Fingerprint(
    definingClass = DETECTOR_PACKAGE,
    name = "onScreenCaptured",
    returnType = "V",
    parameters = listOf(),
)

// Older Android: ContentObserver watching MediaStore for new screenshot files.
private object ScreenshotObserverFingerprint : Fingerprint(
    definingClass = DETECTOR_PACKAGE,
    name = "onChange",
    returnType = "V",
    parameters = listOf("Z", "Landroid/net/Uri;"),
)

@Suppress("unused")
val disableScreenshotDetectionPatch =
    bytecodePatch(
        name = "Disable screenshot detection",
        description = "Stops XChat from detecting screenshots and notifying the other person.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch)

        execute {
            listOf(ScreenCapturedFingerprint, ScreenshotObserverFingerprint).forEach { fingerprint ->
                fingerprint.method.apply {
                    addInstructionsWithLabels(
                        0,
                        """
                        invoke-static {}, $PREF_DESCRIPTOR;->disableScreenshotDetection()Z
                        move-result v0
                        if-eqz v0, :detect
                        return-void
                        """,
                        ExternalLabel("detect", getInstruction(0)),
                    )
                }
            }

            enableSettings("disableScreenshotDetection")
        }
    }
