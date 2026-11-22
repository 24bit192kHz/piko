/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.autoupdate

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.document
import app.morphe.patcher.patch.patch

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/Updates;"
private const val INSTALL_PERMISSION = "android.permission.REQUEST_INSTALL_PACKAGES"

// Android 12+: lets the app update itself without the system asking (once it installed itself).
private const val UPDATE_PERMISSION = "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"

private object UpdatesApplicationOnCreateFingerprint : Fingerprint(
    superclass = "Landroid/app/Application;",
    name = "onCreate",
)

private val autoUpdateManifestPatch =
    patch {
        execute {
            document("AndroidManifest.xml").use { doc ->
                val manifest = doc.documentElement
                val perms = doc.getElementsByTagName("uses-permission")
                listOf(INSTALL_PERMISSION, UPDATE_PERMISSION, "android.permission.SYSTEM_ALERT_WINDOW").forEach { name ->
                    val has = (0 until perms.length).any { (perms.item(it) as org.w3c.dom.Element).getAttribute("android:name") == name }
                    if (!has) {
                        val el = doc.createElement("uses-permission")
                        el.setAttribute("android:name", name)
                        manifest.insertBefore(el, manifest.firstChild)
                    }
                }
                // Opens the app again after an update started from the update prompt.
                val application = doc.getElementsByTagName("application").item(0)
                val receiver = doc.createElement("receiver")
                receiver.setAttribute("android:name", "app.morphe.extension.twitter.patches.UpdateRestart")
                receiver.setAttribute("android:exported", "false")
                val filter = doc.createElement("intent-filter")
                val action = doc.createElement("action")
                action.setAttribute("android:name", "android.intent.action.MY_PACKAGE_REPLACED")
                filter.appendChild(action)
                receiver.appendChild(filter)
                application.appendChild(receiver)
            }
        }
    }

@Suppress("unused")
val autoUpdatePatch =
    patch(
        name = "Update from GitHub",
        description = "Checks the fork's GitHub releases when the app opens (every 6 hours) and offers a newer one: " +
            "Update (downloads, installs and restarts the app), Not now, or Skip this version.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch, autoUpdateManifestPatch)

        execute {
            UpdatesApplicationOnCreateFingerprint.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->init(Landroid/app/Application;)V",
            )
            enableSettings("autoUpdate")
        }
    }
