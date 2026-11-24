/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.text.TextUtils;
import android.view.View;

import com.x.models.interstitial.BlurImageInterstitial;
import com.twitter.model.json.mediavisibility.JsonBlurredImageInterstitial;
import com.twitter.model.json.timeline.urt.JsonTimelineEntry;
import com.twitter.model.json.core.JsonSensitiveMediaWarning;
import com.twitter.model.json.timeline.urt.JsonTimelineModuleItem;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.settings.SettingsStatus;
import app.morphe.extension.twitter.entity.Video;

import java.util.List;
import java.util.ArrayList;
import app.morphe.extension.crimera.PikoUtils;

public class TimelineEntry {
    public static final boolean hideAds;
    private static final boolean hideWTF,hideCTS,hideCTJ,hideDetailedPosts,hideRBMK,hidePinnedPosts,hidePremiumPrompt,showSensitiveMedia,hideTopPeopleSearch,hideTodaysNews,hideExploreTrends,hideExplorePostsForYou;
    // Explore's "Posts For You" is a module "tweet-<module id>" whose items are "tweet-<module id>-tweet-<post id>".
    private static final java.util.regex.Pattern POSTS_FOR_YOU_ITEM = java.util.regex.Pattern.compile("^tweet-(\\d+)-tweet-\\d+$");
    private static final java.util.Set<String> hiddenPostsForYouModules = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    static {
        hideAds = (Pref.hideAds() && SettingsStatus.hideAds);
        hideWTF = (Pref.hideWTF() && SettingsStatus.hideWTF);
        hideCTS = (Pref.hideCTS() && SettingsStatus.hideCTS);
        hideCTJ = (Pref.hideCTJ() && SettingsStatus.hideCTJ);
        hideDetailedPosts = (Pref.hideDetailedPosts() && SettingsStatus.hideDetailedPosts);
        hideRBMK = (Pref.hideRBMK() && SettingsStatus.hideRBMK);
        hidePinnedPosts = (Pref.hideRPinnedPosts() && SettingsStatus.hideRPinnedPosts);
        hidePremiumPrompt = (Pref.hidePremiumPrompt() && SettingsStatus.hidePremiumPrompt);
        showSensitiveMedia = Pref.showSensitiveMedia();
        hideTopPeopleSearch = (Pref.hideTopPeopleSearch() && SettingsStatus.hideTopPeopleSearch);
        hideTodaysNews = (Pref.hideTodaysNews() && SettingsStatus.hideTodaysNews);
        hideExploreTrends = (Pref.hideExploreTrends() && SettingsStatus.hideExploreItems);
        hideExplorePostsForYou = (Pref.hideExplorePostsForYou() && SettingsStatus.hideExploreItems);
    }

    private static boolean isEntryIdRemove(String entryId, Object tweet) {
        String[] split = entryId.split("-");
        String entryId2 = split[0];
        if (!entryId2.equals("cursor") && !entryId2.equals("Guide") && !entryId2.startsWith("semantic_core")) {
            // Ads from accounts on the "Allow ads from" list are kept. Only promoted entries ask (reading the
            // author and the list for every entry was most of this check's cost).
            boolean adEntry = entryId.contains("promoted") || entryId.contains("rtb")
                    || (entryId2.equals("conversationthread") && split.length == 3);
            boolean removeAds = hideAds && adEntry && !SponsoredPosts.isAdAllowed(tweet);
            if ((entryId.contains("promoted") || (entryId2.equals("conversationthread") && split.length == 3)) && removeAds) {
                SponsoredPosts.logHidden("Promoted", tweet);
                return true;
            }
            if ((entryId2.equals("superhero") || entryId2.equals("eventsummary")) && hideAds) {
                return true;
            }
            if (entryId.contains("rtb") && removeAds) {
                SponsoredPosts.logHidden("Promoted", tweet);
                return true;
            }
            if (entryId2.startsWith("tweetdetail") && hideDetailedPosts) {
                return true;
            }
            if (entryId2.equals("bookmarked") && hideRBMK) {
                return true;
            }
            if (entryId.startsWith("community-to-join") && hideCTJ) {
                return true;
            }
            if (entryId.startsWith("who-to-follow") && hideWTF) {
                return true;
            }
            if (entryId.startsWith("who-to-subscribe") && hideCTS) {
                return true;
            }
            if (entryId.startsWith("pinned-tweets") && hidePinnedPosts) {
                return true;
            }
            if (entryId.startsWith("messageprompt-") && hidePremiumPrompt) {
                return true;
            }
            if ((entryId.startsWith("main-event-") || entryId2.equals("pivot")) && hideAds) {
                return true;
            }
            if (entryId2.equals("toptabsrpusermodule") && hideTopPeopleSearch) {
                return true;
            }
            if (entryId.startsWith("stories") && hideTodaysNews) {
                return true;
            }
            if ((entryId2.equals("trend") || entryId2.equals("trends")) && hideExploreTrends) {
                return true;
            }
            if (hideExplorePostsForYou) {
                java.util.regex.Matcher m = POSTS_FOR_YOU_ITEM.matcher(entryId);
                if (m.matches()) {
                    // Module items are parsed before their module entry.
                    hiddenPostsForYouModules.add(m.group(1));
                    return true;
                }
                if (entryId2.equals("tweet") && split.length == 2 && hiddenPostsForYouModules.remove(split[1])) {
                    return true;
                }
            }
        }
        return false;
    }
    // region Threads started by people you don't follow (Twitter's old rule for the home timeline)

    // The first post of the module being parsed (its items come before the module entry): whether to hide it.
    private static final ThreadLocal<Object[]> moduleStarter = new ThreadLocal<>();   // {unfollowed?, post}
    private static volatile long me;

    /** True only when X says explicitly that you don't follow the post's author, and it isn't you. */
    private static boolean authorUnfollowed(Object tweet) {
        try {
            Object author = SponsoredPosts.getAuthor(tweet);
            if (author == null) return false;
            app.morphe.extension.twitter.entity.TwitterUser user = new app.morphe.extension.twitter.entity.TwitterUser(author);
            Integer friendship = user.getFriendship();
            if (friendship == null || (friendship & 1) != 0 || (friendship & 128) != 0) return false; // following, or not known
            if (me == 0) me = Long.parseLong(com.twitter.util.user.UserIdentifier.getCurrent().getStringId());
            return user.getId() != me;
        } catch (Throwable t) {
            return false;
        }
    }

    // endregion

    public static JsonTimelineEntry checkEntry(JsonTimelineEntry jsonTimelineEntry) {
        Object[] starter = moduleStarter.get();
        moduleStarter.remove();
        try {
            String entryId = jsonTimelineEntry.a;
            Object tweet = SponsoredPosts.takeEntryTweet();
            if (isEntryIdRemove(entryId, tweet) || SponsoredPosts.shouldHide(tweet) || Withheld.hideEntry(entryId)) {
                return null;
            }
            if (starter != null && Boolean.TRUE.equals(starter[0]) && entryId != null && entryId.startsWith("home-conversation-")
                    && MuteFeatures.hideUnfollowedThreads()) {
                SponsoredPosts.logHidden("Thread started by someone you don't follow", starter[1]);
                return null;
            }
        } catch (Exception ignored) {
        }
        return jsonTimelineEntry;
    }
    public static JsonTimelineModuleItem checkEntry(JsonTimelineModuleItem jsonTimelineModuleItem) {
        try {
            String entryId = jsonTimelineModuleItem.a;
            Object tweet = SponsoredPosts.takeEntryTweet();
            if (moduleStarter.get() == null && MuteFeatures.hideUnfollowedThreads()) moduleStarter.set(new Object[]{authorUnfollowed(tweet), tweet});
            if (isEntryIdRemove(entryId, tweet) || SponsoredPosts.shouldHide(tweet) || Withheld.hideEntry(entryId)) {
                return null;
            }
        } catch (Exception ignored) {
        }
        return jsonTimelineModuleItem;
    }
    // Interface to reset obfuscated fields
    // This is one of the methods to avoid using Java Reflection, which has high overhead
    public interface JsonBlurredImageInterstitialPatchInterface {
        // Method is added during patching
        void patch_showSensitiveMedia();
    }
    public interface JsonSensitiveMediaWarningPatchInterface {
        // Method is added during patching
        void patch_showSensitiveMedia();
    }
    public static JsonBlurredImageInterstitial showSensitiveMedia(JsonBlurredImageInterstitialPatchInterface patchInterface) {
        if (showSensitiveMedia && patchInterface != null) {
            patchInterface.patch_showSensitiveMedia();
        }
        return (JsonBlurredImageInterstitial) patchInterface;
    }
    public static JsonSensitiveMediaWarning showSensitiveMedia(JsonSensitiveMediaWarningPatchInterface patchInterface) {
        if (showSensitiveMedia && patchInterface != null) {
            patchInterface.patch_showSensitiveMedia();
        }
        return (JsonSensitiveMediaWarning) patchInterface;
    }
    public static BlurImageInterstitial showSensitiveMedia(BlurImageInterstitial interstitial) {
        return showSensitiveMedia ? null : interstitial;
    }
    public static void showSensitiveImage(View view) {
        if (showSensitiveMedia && view != null) {
            // Click the 'Show' button on the timeline to make the blurred image visible
            Utils.runOnMainThread(view::callOnClick);
        }
    }
    // Caution: This profile may include potentially sensitive content
    private static final String sensitiveProfileHeader = str("profile_interstitial_sensitive_media_header");
    private static boolean isSensitiveProfile = false;
    public static int setSensitiveProfileWarningDialogTitle(String title, int visibility) {
        if (showSensitiveMedia) {
            // Check the title of the alert dialog to prevent other profile warnings (such as racism or terrorism) from closing
            isSensitiveProfile = TextUtils.equals(sensitiveProfileHeader, title);

            if (isSensitiveProfile) {
                // If it is a general sensitive media warning, hide the alert dialog
                return View.GONE;
            }
        }

        return visibility;
    }
    public static void showSensitiveProfile(View view) {
        if (isSensitiveProfile && view != null) {
            // If it is a general sensitive media warning, also click the dismiss button on the alert dialog
            // This is to prevent the UI from breaking due to incorrect WindowInsets calculations, even though the alert dialog is hidden
            Utils.runOnMainThread(view::callOnClick);
        }
    }
    public static boolean hidePromotedTrend(Object data) {
        return data != null && hideAds;
    }

    private static final java.util.regex.Pattern VARIANT_SIZE = java.util.regex.Pattern.compile("/(\\d{2,5})x(\\d{2,5})/");
    private static java.util.List<android.media.MediaCodecInfo.VideoCapabilities> avcDecoders;

    /** Whether an H.264 decoder on this device supports the variant's size (from its URL); true when unknown. */
    static boolean decodable(String url) {
        try {
            java.util.regex.Matcher m = url == null ? null : VARIANT_SIZE.matcher(url);
            if (m == null || !m.find()) return true;
            int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2));
            if (avcDecoders == null) {
                java.util.List<android.media.MediaCodecInfo.VideoCapabilities> caps = new java.util.ArrayList<>();
                for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (info.isEncoder()) continue;
                    for (String type : info.getSupportedTypes()) {
                        if (type.equalsIgnoreCase("video/avc")) caps.add(info.getCapabilitiesForType(type).getVideoCapabilities());
                    }
                }
                avcDecoders = caps;
            }
            if (avcDecoders.isEmpty()) return true;
            for (android.media.MediaCodecInfo.VideoCapabilities c : avcDecoders) {
                if (c != null && (c.isSizeSupported(w, h) || c.isSizeSupported(h, w))) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    public static List<Object> timelineVideos(List<Object> videoEntities){
        int maxBitrate = 0;
        Object maxVideoObject = null;
        try{
            if(Pref.ENABLE_FORCE_HD) {
                for (Object vidObj : videoEntities) {
                    Video vid = new Video(vidObj);
                    String mediaExt = vid.getExtension();
                    if (!(mediaExt.equals("mp4"))) continue;

                    int bitrate = vid.getBitrate();
                    if(bitrate<maxBitrate) continue;
                    // Not a size this device's video decoders can play (e.g. 4K on many phones): upstream #1669.
                    if (!decodable(vid.getMediaUrl())) continue;
                    maxBitrate = bitrate;
                    maxVideoObject = vidObj;
                }
                if (maxVideoObject != null) {
                    ArrayList<Object> result = new ArrayList<>();
                    result.add(maxVideoObject);
                    return result;
                }
            }

        } catch (Exception ex) {
            PikoUtils.logger(ex);
        }

        return videoEntities;
    }

//end
}
