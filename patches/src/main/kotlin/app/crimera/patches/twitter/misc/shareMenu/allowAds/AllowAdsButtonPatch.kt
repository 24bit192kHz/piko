/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.shareMenu.allowAds

import app.crimera.patches.twitter.ads.timelineEntryHook.hideSponsoredPostsPatch
import app.crimera.patches.twitter.entity.entityGenerator
import app.crimera.patches.twitter.entity.twitterUser.twitterUserEntity
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.misc.shareMenu.hooks.shareMenuButtonInjection
import app.crimera.patches.twitter.misc.shareMenu.hooks.shareMenuButtonOnClickHook
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.versionCheckPatch
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.util.indexOfFirstLiteralInstruction
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/SponsoredPosts;"
private const val USER_CLASS = "Lcom/twitter/model/core/entity/k1;"

/** The profile screen's onCreateOptionsMenu: inflates R.menu.profile_toolbar. */
private object ProfileCreateOptionsMenuFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L", "Landroid/view/Menu;"),
    filters = listOf(resourceLiteral(ResourceType.MENU, "profile_toolbar")),
)

@Suppress("unused")
val allowAdsButtonPatch =
    bytecodePatch(
        name = "Allow ads from account button",
        description = "Adds a share menu and profile menu button that adds or removes the account from the \"Allow ads from\" list.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(
            settingsPatch,
            entityGenerator,
            twitterUserEntity,
            versionCheckPatch,
            shareMenuButtonOnClickHook,
            hideSponsoredPostsPatch,
            resourceMappingPatch,
        )

        execute {
            val actionName = "AllowAds"
            val prefFunctionName = "enableAllowAdsButton"
            val stringId = "piko_allow_ads_from_account"
            val iconId = "ic_vector_megaphone"
            val statusFunctionName = "allowAdsButton"
            shareMenuButtonInjection(actionName, prefFunctionName, stringId, iconId, statusFunctionName)

            // Profile overflow menu.
            ProfileCreateOptionsMenuFingerprint.apply {
                // The menu register is reused right after inflating, so add the item straight after.
                val inflateIndex = instructionMatches.first().index + 1
                method.addInstruction(
                    inflateIndex + 1,
                    "invoke-static {p2}, $EXTENSION_CLASS->addProfileMenuItem(Landroid/view/Menu;)V",
                )

                val profileClass = mutableClassDefBy(classDef)
                // The viewed profile's user; the final user field is the signed-in account.
                val userField =
                    profileClass.fields.singleOrNull { it.type == USER_CLASS && !AccessFlags.FINAL.isSet(it.accessFlags) }
                        ?: throw PatchException("Profile user field not found")

                // onOptionsItemSelected: handles R.id.menu_add_remove_from_list among others.
                val listItemId = getResourceId(ResourceType.ID, "menu_add_remove_from_list")
                val onItemSelected =
                    profileClass.methods.firstOrNull { m ->
                        m.returnType == "Z" &&
                            m.parameterTypes == listOf("Landroid/view/MenuItem;") &&
                            m.indexOfFirstLiteralInstruction(listItemId) >= 0
                    } ?: throw PatchException("Profile onOptionsItemSelected not found")

                onItemSelected.addInstructionsWithLabels(
                    0,
                    """
                    move-object/from16 v0, p0
                    iget-object v0, v0, $userField
                    move-object/from16 v1, p1
                    invoke-static {v0, v1}, $EXTENSION_CLASS->onProfileMenuItemSelected(Ljava/lang/Object;Landroid/view/MenuItem;)Z
                    move-result v0
                    if-eqz v0, :piko_not_allow_ads
                    const/4 v0, 0x1
                    return v0
                    """.trimIndent(),
                    ExternalLabel("piko_not_allow_ads", onItemSelected.getInstruction(0)),
                )
            }
        }
    }
