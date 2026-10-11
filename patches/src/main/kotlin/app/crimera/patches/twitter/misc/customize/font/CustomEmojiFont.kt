/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.customize.font

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import java.io.File

// Must match UpdateFont.BUNDLED_EMOJI_FONT_ASSET in the extension.
private const val BUNDLED_EMOJI_FONT_ASSET = "assets/piko/emoji_font.ttf"

private val customEmojiFontSettingsPatch =
    bytecodePatch {
        dependsOn(customFontHook, settingsPatch)

        execute {
            enableSettings("customEmojiFont")
        }
    }

@Suppress("unused")
val customEmojiFont =
    resourcePatch(
        name = "Custom emoji font",
        description = "Customise emoji font style",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(customEmojiFontSettingsPatch)

        val bundledFontPath by stringOption(
            key = "bundledFontPath",
            default = null,
            title = "Bundled emoji font",
            description =
                "Path to a .ttf emoji font (CBDT/COLR) to bundle into the app. " +
                    "It is used until a font is picked in Piko settings.",
        )

        execute {
            val path = bundledFontPath?.takeIf { it.isNotBlank() } ?: return@execute
            val source = File(path)
            if (!source.isFile) throw PatchException("Bundled emoji font not found: $path")

            val target = get(BUNDLED_EMOJI_FONT_ASSET, copy = false)
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }
