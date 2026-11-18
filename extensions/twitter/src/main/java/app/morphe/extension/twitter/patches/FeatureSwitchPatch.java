/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import static app.morphe.extension.twitter.patches.VersionCheckPatch.IS_11_95_OR_GREATER;

import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.settings.Settings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class FeatureSwitchPatch {
    public static String FLAGS_SEARCH = "";
    // Membership check for addFeatureFlagSearchItem() below, which runs on every single Boolean
    // feature flag read anywhere in the app - a String.contains() scan over the whole
    // (unbounded, ever-growing) FLAGS_SEARCH string on every call was the actual cost there.
    // Kept alongside FLAGS_SEARCH itself since FeatureFlagCatalog reads that field directly.
    private static final Set<String> FLAGS_SEARCH_SET = new HashSet<>();
    // flagInfo() below can be called from any thread, so the set, the string, and the pref write
    // all update under the same lock, and getFeatureFlagSearchItems() rebuilds under it too.
    private static final Object FLAGS_SEARCH_LOCK = new Object();

    private static final HashMap<String, Object> FLAGS = new HashMap<>();

    private static void addFlag(String flag, Object val) {
        FLAGS.put(flag, val);
    }

    private static void fabMenu() {
        addFlag("android_compose_fab_menu_enabled", Pref.hideFABBtn());
    }

    // Grok: post buttons, timeline/ad/trend analysis, badges, share/composer entries and the upsell prompts.
    private static void hideGrok() {
        if (!Pref.hideGrok()) return;
        String[] flags = {
                "grok_analysis_button_enabled", "grok_android_analyze_timelines_enabled",
                "grok_android_analyze_ads_enabled", "grok_android_analyze_trend_fetch_enabled",
                "grok_android_tab_badge_enabled", "grok_share_attachment_enabled",
                "grok_inline_sharecard_ask_grok_enabled", "grok_mobile_post_composer_is_enabled",
                "grok_android_imagine_cta_enabled", "grok_android_imagine_create_your_own_enabled",
                "grok_android_download_grok_cta_enabled", "grok_android_google_play_inline_install_enabled",
                "grok_android_google_play_deep_link_redirect_enabled", "grok_android_account_link_enabled",
        };
        for (String f : flags) addFlag(f, false);
    }

    private static void chirpFont() {
        addFlag("af_ui_chirp_enabled", Pref.isChirpFontEnabled());
    }

    private static void hideGoogleAds() { addFlag("ssp_ads_dsp_client_context_enabled", !Pref.hideAds()); }
    private static void viewCount() {
        addFlag("view_counts_public_visibility_enabled", Pref.hideViewCount());
    }

    private static void bookmarkInTimeline() {
        addFlag("bookmarks_in_timelines_enabled", Pref.hideInlineBookmark());
    }

    private static void navbarFix() {
        addFlag("subscriptions_feature_1008", true);

        if (IS_11_95_OR_GREATER) {
            // Added in 11.95.0-aplha.0. This flag should be disabled for the patch to work properly.
            addFlag("subscriptions_feature_1008_sunset", false);
        }
    }

    private static void hdUploads() {
        if (!Pref.hdUploads()) return;
        // Photos: X's "high quality" path (4096 px) for every network condition, not only on Wi-Fi.
        addFlag("android_photo_upload_high_quality_enabled", true);
        for (String tier : new String[]{"poor", "good", "great", "high_quality_images_upload"}) {
            addFlag("photo_upload_" + tier + "_default_resolution", 4096);
            addFlag("photo_upload_" + tier + "_default_quality", 85);
        }
        // Videos: X's high-quality (720p) uploads on mobile data too. 1080p isn't possible without
        // Premium: X's server serves non-Premium uploads at 720p even when the 1080p file arrives intact.
        addFlag("android_high_quality_720p_video_upload_setting_default", "wifi_and_mobile");
    }

    private static void immersivePlayer() {
        addFlag("explore_relaunch_enable_immersive_player_across_twitter", Pref.hideImmersivePlayer());
    }

    private static void restoreLegacyFollowerLists() {
        addFlag("android_follower_timelines_stack_enabled", false);
    }

    // X pre-warms a hidden WebView on every start (Chromium init in the app process, a sandboxed
    // webview process, the responsive-web "castle" fingerprinting bundle) and prefetches ad
    // landing pages plus the home timeline through the ads (jetfuel) pipeline. None of it is
    // user-visible; the in-app browser and the timeline still work on demand. load() parses the
    // user's own feature-flag overrides after this, so a flag set in the Piko feature-flags UI wins.
    private static void quietStartup() {
        String[] flags = {
                "android_webview_eager_preload_enabled",
                "android_webview_eager_preload_tfa_enabled",
                "android_webview_paywall_preload_enabled",
                "android_webview_grok_post_preload_enabled",
                "android_ad_formats_browser_destination_prefetching_enabled",
                "android_ad_formats_browser_destination_prefetching_likely_open_link_enabled",
                "x_android_jetfuel_prefetch_home_timeline_action_enabled",
        };
        for (String f : flags) addFlag(f, false);
    }

    public static void getFeatureFlagSearchItems() {
        synchronized (FLAGS_SEARCH_LOCK) {
            FLAGS_SEARCH = Utils.getStringPref(Settings.MISC_FEATURE_FLAGS_SEARCH);
            FLAGS_SEARCH_SET.clear();
            if (!FLAGS_SEARCH.isEmpty()) {
                FLAGS_SEARCH_SET.addAll(Arrays.asList(FLAGS_SEARCH.split(",")));
            }
        }
    }

    public static void addFeatureFlagSearchItem(String flag) {
        synchronized (FLAGS_SEARCH_LOCK) {
            if (!FLAGS_SEARCH_SET.add(flag)) {
                return;
            }

            FLAGS_SEARCH = FLAGS_SEARCH.concat(flag + ",");
            Utils.setStringPref(Settings.MISC_FEATURE_FLAGS_SEARCH.key, FLAGS_SEARCH);
        }
    }

    private static void removePremiumUpsell() {
        boolean isDisabled = Pref.removePremiumUpsell(); // The return value is inverted
        if (isDisabled) {
            return;
        }
        addFlag("subscriptions_enabled", false);
        addFlag("subscriptions_upsells_get_verified_profile", false);
        addFlag("subscriptions_upsells_get_verified_drawer_discount_enabled", false);
        addFlag("subscriptions_upsells_get_verified_profile_fatigue_enabled", false);
        addFlag("subscriptions_upsells_api_enabled", false);
        addFlag("subscriptions_upsells_user_profile_name_migration_enabled", false);
        addFlag("subscriptions_upsells_profile_card_enable", false);
        addFlag("subscriptions_upsells_get_verified_drawer_card_enabled", false);
        addFlag("subscriptions_upsells_get_verified_profile_discount_visitor_enabled", false);
        addFlag("subscriptions_upsells_analytics_profile_enabled", false);
        addFlag("subscriptions_upsells_articles_post_composer_promo_variant_enabled", false);
        addFlag("subscriptions_upsells_bookmark_folders_enabled", false);
        addFlag("subscriptions_upsells_verified_profile_visitor_upsell_enabled", false);
        addFlag("subscriptions_upsells_verified_profile_visitor_upsell_redesign_enabled", false);
        addFlag("subscriptions_upsells_get_verified_profile_card", false);
        addFlag("subscriptions_upsells_get_verified_profile_discount_own_enabled", false);
        addFlag("subscriptions_upsells_get_verified_profile_rotation_enabled", false);
        addFlag("subscriptions_upsells_home_nav_migration_enabled", false);
        addFlag("subscriptions_upsells_profile_card_enabled", false);
        addFlag("subscriptions_upsells_quick_display_settings", false);
        addFlag("subscriptions_upsells_track_interactions_enabled", false);
        addFlag("subscriptions_upsells_user_profile_header_migration_enabled", false);

    }

    public static Object flagInfo(String flag, Object def) {
        try {
            if (def instanceof Boolean) {
                addFeatureFlagSearchItem(flag);
            }
            if (FLAGS.containsKey(flag)) {
                return FLAGS.get(flag);
            }
        } catch (Exception ex) {

        }
        return def;
    }

    public static void load() {
        quietStartup();
        String flags = Utils.getStringPref(Settings.MISC_FEATURE_FLAGS);
        if (!flags.isEmpty()) {
            for (String flag : flags.split(",")) {
                String[] item = flag.split(":");
                addFlag(item[0], Boolean.valueOf(item[1]));
            }
        }
    }
}
