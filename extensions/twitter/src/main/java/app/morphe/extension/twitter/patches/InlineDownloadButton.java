package app.morphe.extension.twitter.patches;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.patches.nativeFeatures.downloader.NativeDownloader;

@SuppressWarnings("unused")
public class InlineDownloadButton {

    private static final String WRAPPER_TAG = "piko_download_wrapper";
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    // Placeholders rewritten at patch time.
    private static String getTweetFieldName() {
        return "mTweet";
    }

    private static String getIconColorFieldName() {
        return "mIconDrawableColorStateList";
    }

    private static String getRenderHintsFieldName() {
        return "mRenderHints";
    }

    private static String getRenderHintsFocalFieldName() {
        return "isFocalTweet";
    }

    private static final String DISLIKE_TAG = "piko_dislike";

    private static boolean downloadEnabled() {
        try {
            return Pref.enableNativeDownloader() && Pref.enableInlineDownloadButton();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void onFinishInflate(ViewGroup inlineActionBar) {
        boolean download = downloadEnabled();
        boolean dislike = DislikeButton.enabled();
        if (!download && !dislike) return;
        inlineActionBar.post(() -> wrapWithDownloadButton(inlineActionBar, download, dislike));
    }

    /**
     * Injection point: the action bar shows another post. The "Not interested" button is shown
     * when X offers that feedback for the post (after the rest of the row is bound).
     */
    public static void onSetTweet(ViewGroup inlineActionBar) {
        boolean dislike = DislikeButton.enabled();
        if (dislike || MutedWords.menuEnabled() || MutedWords.active()) {
            try {
                // Now, while the row is bound: a muted post's row is hidden before it is drawn.
                Object tweet = readField(inlineActionBar, getTweetFieldName());
                DislikeButton.onBar(inlineActionBar, tweet);
                MutedWords.onBar(inlineActionBar, tweet, isFocalTweet(inlineActionBar));
            } catch (Throwable t) {
                if (DislikeButton.DEBUG) DislikeButton.debug("bind error " + t);
            }
        }
        if (dislike) inlineActionBar.post(() -> updateDislike(inlineActionBar));
    }

    /** The post the action bar shows, or null. */
    static Object tweetOf(ViewGroup inlineActionBar) {
        try {
            return readField(inlineActionBar, getTweetFieldName());
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean focal(ViewGroup inlineActionBar) {
        return isFocalTweet(inlineActionBar);
    }

    static void refreshDislike(ViewGroup inlineActionBar) {
        updateDislike(inlineActionBar);
    }

    private static void updateDislike(ViewGroup inlineActionBar) {
        long t = app.morphe.extension.twitter.patches.country.Perf.start();
        updateDislike0(inlineActionBar);
        app.morphe.extension.twitter.patches.country.Perf.end(3, t);
    }

    private static void updateDislike0(ViewGroup inlineActionBar) {
        ViewParent parent = inlineActionBar.getParent();
        if (!(parent instanceof ViewGroup)) return;
        View dislike = ((ViewGroup) parent).findViewWithTag(DISLIKE_TAG);
        if (dislike == null) return;
        boolean show = false;
        try {
            Object tweet = readField(inlineActionBar, getTweetFieldName());
            DislikeButton.onBar(inlineActionBar, tweet);
            if (!isFocalTweet(inlineActionBar)) show = DislikeButton.available(tweet);
            if (DislikeButton.DEBUG) DislikeButton.debug("bar " + show);
        } catch (Exception e) {
            if (DislikeButton.DEBUG) DislikeButton.debug("bar error " + e);
        }
        int visibility = show ? View.VISIBLE : View.GONE;
        if (dislike.getVisibility() != visibility) dislike.setVisibility(visibility);
    }

    private static Field getField(Class<?> targetClass, String fieldName) throws NoSuchFieldException {
        Map<String, Field> classFields = FIELD_CACHE.computeIfAbsent(targetClass, ignored -> new ConcurrentHashMap<>());
        Field field = classFields.get(fieldName);
        if (field != null) return field;

        Field declaredField = targetClass.getDeclaredField(fieldName);
        declaredField.setAccessible(true);
        classFields.put(fieldName, declaredField);
        return declaredField;
    }

    private static Object readField(Object target, String fieldName) throws ReflectiveOperationException {
        return getField(target.getClass(), fieldName).get(target);
    }

    private static boolean isFocalTweet(ViewGroup inlineActionBar) {
        try {
            Object renderHints = readField(inlineActionBar, getRenderHintsFieldName());
            if (renderHints == null) return false;
            return Boolean.TRUE.equals(readField(renderHints, getRenderHintsFocalFieldName()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int getActionCount(ViewGroup inlineActionBar) {
        int count = 0;
        for (int i = 0; i < inlineActionBar.getChildCount(); i++) {
            View child = inlineActionBar.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private static View getLastVisibleChild(ViewGroup inlineActionBar) {
        for (int i = inlineActionBar.getChildCount() - 1; i >= 0; i--) {
            View child = inlineActionBar.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0) {
                return child;
            }
        }
        return null;
    }

    private static void syncButtonStyle(ViewGroup inlineActionBar, FrameLayout downloadContainer, ImageView downloadIcon) {
        View lastVisibleChild = getLastVisibleChild(inlineActionBar);
        if (!(lastVisibleChild instanceof ViewGroup)) return;

        ViewGroup referenceGroup = (ViewGroup) lastVisibleChild;
        for (int i = 0; i < referenceGroup.getChildCount(); i++) {
            View child = referenceGroup.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;

            ViewGroup container = (ViewGroup) child;
            ImageView referenceIcon = Utils.getChildView(
                    container,
                    true,
                    view -> view instanceof ImageView && view.getVisibility() == View.VISIBLE
            );
            if (referenceIcon == null) continue;

            applyIconContainerStyle(container, referenceIcon, downloadContainer, downloadIcon);
            return;
        }
    }

    private static void applyIconContainerStyle(
            ViewGroup referenceContainer,
            ImageView referenceIcon,
            FrameLayout downloadContainer,
            ImageView downloadIcon
    ) {
        if (downloadContainer.getPaddingLeft() != referenceContainer.getPaddingLeft()
                || downloadContainer.getPaddingTop() != referenceContainer.getPaddingTop()
                || downloadContainer.getPaddingRight() != referenceContainer.getPaddingRight()
                || downloadContainer.getPaddingBottom() != referenceContainer.getPaddingBottom()) {
            downloadContainer.setPadding(
                    referenceContainer.getPaddingLeft(),
                    referenceContainer.getPaddingTop(),
                    referenceContainer.getPaddingRight(),
                    referenceContainer.getPaddingBottom());
        }

        if (downloadIcon.getScaleType() != referenceIcon.getScaleType()) {
            downloadIcon.setScaleType(referenceIcon.getScaleType());
        }

        ViewGroup.LayoutParams referenceLayoutParams = referenceIcon.getLayoutParams();
        int targetWidth = referenceLayoutParams != null
                ? referenceLayoutParams.width
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        int targetHeight = referenceLayoutParams != null
                ? referenceLayoutParams.height
                : ViewGroup.LayoutParams.WRAP_CONTENT;

        ViewGroup.LayoutParams currentLayoutParams = downloadIcon.getLayoutParams();
        if (currentLayoutParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams currentFrameLayoutParams = (FrameLayout.LayoutParams) currentLayoutParams;
            if (currentFrameLayoutParams.width == targetWidth
                    && currentFrameLayoutParams.height == targetHeight
                    && currentFrameLayoutParams.gravity == Gravity.CENTER) {
                return;
            }
        }

        FrameLayout.LayoutParams downloadIconLayoutParams = new FrameLayout.LayoutParams(
                targetWidth,
                targetHeight,
                Gravity.CENTER);
        downloadIcon.setLayoutParams(downloadIconLayoutParams);
    }

    private static ColorStateList resolveIconColor(ViewGroup inlineActionBar) {
        String colorFieldName = getIconColorFieldName();
        for (int i = 0; i < inlineActionBar.getChildCount(); i++) {
            View child = inlineActionBar.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;

            try {
                Object value = readField(child, colorFieldName);
                if (value instanceof ColorStateList) {
                    return (ColorStateList) value;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void updateFocalLayoutWeights(
            ViewGroup inlineActionBar,
            FrameLayout downloadContainer,
            LinearLayout.LayoutParams barLayoutParams,
            LinearLayout.LayoutParams downloadLayoutParams,
            int[] lastActionCount
    ) {
        int targetCount = getActionCount(inlineActionBar);
        if (lastActionCount[0] == targetCount) return;
        lastActionCount[0] = targetCount;

        if (barLayoutParams.weight != targetCount) {
            barLayoutParams.weight = targetCount;
            inlineActionBar.setLayoutParams(barLayoutParams);
        }

        if (downloadLayoutParams.width != 0 || downloadLayoutParams.weight != 1.0f) {
            downloadLayoutParams.width = 0;
            downloadLayoutParams.weight = 1.0f;
            downloadContainer.setLayoutParams(downloadLayoutParams);
        }
    }

    private static void wrapWithDownloadButton(ViewGroup inlineActionBar, boolean download, boolean dislike) {
        ViewParent currentParent = inlineActionBar.getParent();
        if (currentParent instanceof LinearLayout) {
            LinearLayout existingWrapper = (LinearLayout) currentParent;
            if (WRAPPER_TAG.equals(existingWrapper.getTag())) {
                return;
            }
        }
        if (!(currentParent instanceof ViewGroup)) return;

        ViewGroup parent = (ViewGroup) currentParent;

        try {
            int index = parent.indexOfChild(inlineActionBar);
            ViewGroup.LayoutParams originalLayoutParams = inlineActionBar.getLayoutParams();
            Context context = inlineActionBar.getContext();

            parent.removeView(inlineActionBar);

            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.HORIZONTAL);
            wrapper.setTag(WRAPPER_TAG);
            wrapper.setGravity(Gravity.CENTER_VERTICAL);

            boolean distributeEvenly = isFocalTweet(inlineActionBar);
            int actionCount = distributeEvenly ? getActionCount(inlineActionBar) : 1;

            LinearLayout.LayoutParams barLayoutParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    actionCount);
            wrapper.addView(inlineActionBar, barLayoutParams);

            if (dislike) {
                FrameLayout dislikeContainer = new FrameLayout(context);
                dislikeContainer.setTag(DISLIKE_TAG);
                dislikeContainer.setClickable(true);
                dislikeContainer.setFocusable(true);
                dislikeContainer.setContentDescription(AdvancedSearch.t(dislikeContainer.getContext(), "piko_ni_button", "Not interested in this post"));
                ImageView dislikeIcon = new ImageView(context);
                int dislikeIconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "piko_ic_not_interested");
                if (dislikeIconId != 0) dislikeIcon.setImageResource(dislikeIconId);
                dislikeIcon.setScaleType(ImageView.ScaleType.CENTER);
                dislikeContainer.addView(dislikeIcon, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER));
                ColorStateList dislikeColor = resolveIconColor(inlineActionBar);
                if (dislikeColor != null && dislikeIcon.getDrawable() != null) {
                    Drawable tinted = dislikeIcon.getDrawable().mutate();
                    tinted.setTintList(dislikeColor);
                    dislikeIcon.setImageDrawable(tinted);
                }
                dislikeContainer.setVisibility(View.GONE);
                dislikeContainer.setOnClickListener(v -> {
                    try {
                        DislikeButton.dismiss(inlineActionBar, readField(inlineActionBar, getTweetFieldName()));
                    } catch (Exception e) {
                        Logger.printException(() -> "not interested error", e);
                    }
                });
                wrapper.addView(dislikeContainer, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
                wrapper.post(() -> {
                    syncButtonStyle(inlineActionBar, dislikeContainer, dislikeIcon);
                    updateDislike(inlineActionBar);
                });
            }

            if (download) {
                FrameLayout downloadContainer = new FrameLayout(context);
                downloadContainer.setClickable(true);
                downloadContainer.setLongClickable(true);
                downloadContainer.setFocusable(true);
                downloadContainer.setContentDescription(NativeDownloader.downloadString());

                ImageView downloadIcon = new ImageView(context);
                int iconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "ic_vector_incoming");
                if (iconId != 0) {
                    downloadIcon.setImageResource(iconId);
                }
                downloadIcon.setScaleType(ImageView.ScaleType.CENTER);
                downloadContainer.addView(downloadIcon, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER));

                ColorStateList iconColor = resolveIconColor(inlineActionBar);
                if (iconColor != null) {
                    Drawable drawable = downloadIcon.getDrawable();
                    if (drawable != null) {
                        Drawable tintedDrawable = drawable.mutate();
                        tintedDrawable.setTintList(iconColor);
                        downloadIcon.setImageDrawable(tintedDrawable);
                    }
                }

                LinearLayout.LayoutParams downloadLayoutParams = distributeEvenly
                        ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
                        : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                wrapper.addView(downloadContainer, downloadLayoutParams);
                wrapper.post(() -> syncButtonStyle(inlineActionBar, downloadContainer, downloadIcon));

                if (distributeEvenly) {
                    int[] lastActionCount = {actionCount};
                    wrapper.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                            updateFocalLayoutWeights(
                                    inlineActionBar,
                                    downloadContainer,
                                    barLayoutParams,
                                    downloadLayoutParams,
                                    lastActionCount));
                }

                downloadContainer.setOnClickListener(v -> {
                    try {
                        Object tweet = readField(inlineActionBar, getTweetFieldName());
                        if (tweet == null) {
                            Utils.showToastShort("No tweet data");
                            return;
                        }

                        NativeDownloader.downloader(context, tweet);
                    } catch (Exception e) {
                        Logger.printException(() -> "click error", e);
                    }
                });

                downloadContainer.setOnLongClickListener(v -> {
                    try {
                        Object tweet = readField(inlineActionBar, getTweetFieldName());
                        if (tweet == null) {
                            Utils.showToastShort("No tweet data");
                            return true;
                        }

                        NativeDownloader.downloadAllFromTweet(context, tweet);
                    } catch (Exception e) {
                        Logger.printException(() -> "long-press error", e);
                    }
                    return true;
                });
            }

            parent.addView(wrapper, index, originalLayoutParams);
        } catch (Exception e) {
            Logger.printException(() -> "wrap failed", e);
        }
    }
}
