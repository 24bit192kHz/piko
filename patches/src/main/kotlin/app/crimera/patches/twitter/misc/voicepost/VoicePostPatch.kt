/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.voicepost

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val voicePostPatch =
    bytecodePatch(
        name = "Voice posts",
        description = "Adds \"Record voice post\" to Piko settings: records your voice and opens the composer with it attached as a short video (X for Android cannot make audio posts).",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch)

        execute {
            enableSettings("voicePost")
        }
    }
