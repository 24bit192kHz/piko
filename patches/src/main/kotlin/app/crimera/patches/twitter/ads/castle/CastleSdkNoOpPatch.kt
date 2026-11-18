/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.ads.castle

import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

// X's Castle wrapper (com.x.castle.impl.e) configures the Castle.io device-fingerprint SDK from
// the Jetfuel "castle" app action on every start: MediaDrm probing, a persistent
// "castle-monitor-queue" on disk, background event collection and, through the eager WebView
// preload, X's responsive-web "castle" fingerprinting bundle. No user-visible feature reads it;
// X's own SSO paths submit "without a castle_token" when the SDK reports NotInitialized, which
// is exactly the state this leaves the wrapper in (its configured flag stays false).
private object CastleClientConfigureFingerprint : Fingerprint(
    definingClass = "Lcom/x/castle/impl/e;",
    name = "b",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

@Suppress("unused")
val castleSdkNoOpPatch =
    bytecodePatch(
        name = "No-op Castle fingerprint SDK",
        description =
            "Skips configuring Castle.io at startup (MediaDrm probing, a disk queue and " +
                "background event collection). Login flows already handle a missing Castle token.",
    ) {
        compatibleWith(COMPATIBILITY_X)

        execute {
            CastleClientConfigureFingerprint.method.apply {
                addInstructions(0, "return-void")
            }
        }
    }
