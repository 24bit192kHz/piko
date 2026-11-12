/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.grok;

import app.morphe.extension.twitter.Pref;

@SuppressWarnings("unused")
public class HideGrokPatch {
    /** Injection point: X marks each post whether it shows the Grok analysis button. */
    public static boolean grokAnalysisButton(boolean serverValue) {
        try {
            return serverValue && !Pref.hideGrok();
        } catch (Throwable t) {
            return serverValue;
        }
    }
}
