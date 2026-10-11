/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.customise.font;

import static app.morphe.extension.shared.StringRef.str;

import android.os.Build;
import app.morphe.extension.crimera.PikoUtils;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.TypefaceSpan;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import app.morphe.extension.twitter.settings.SettingsStatus;

public class UpdateFont {
    public static final String FONT_FILE_NAME = "custom_font.ttf";
    public static final String EMOJI_FONT_FILE_NAME = "custom_emoji_font.ttf";
    // Added by the "Custom emoji font" patch when a bundled font is set.
    private static final String BUNDLED_EMOJI_FONT_ASSET = "piko/emoji_font.ttf";
    private static final String BUNDLED_EMOJI_FONT_FILE_NAME = "bundled_emoji_font.ttf";

    private static Typeface textTypeface;
    private static Typeface emojiTypeface;
    private static final Context context;
    private static final boolean isCustomFontEnabled;
    private static final boolean isCustomEmojiFontEnabled;
    private static final Pattern emojiPattern;

    static {
        context = app.morphe.extension.shared.Utils.getContext();
        isCustomFontEnabled = SettingsStatus.customFont;
        isCustomEmojiFontEnabled = SettingsStatus.customEmojiFont;
        String base = "[\\x{1F000}-\\x{1FAFF}\\x{2190}-\\x{21FF}\\x{2300}-\\x{23FF}\\x{2460}-\\x{24FF}"
                + "\\x{25A0}-\\x{27BF}\\x{2900}-\\x{297F}\\x{2B00}-\\x{2BFF}\\x{E0020}-\\x{E007F}"
                + "\\x{00A9}\\x{00AE}\\x{203C}\\x{2049}\\x{2122}\\x{2139}\\x{3030}\\x{303D}\\x{3297}\\x{3299}]";
        String keycap = "[#*0-9]\\x{FE0F}?\\x{20E3}";
        // An emoji or keycap, plus any ZWJ sequence, variation selector, skin tone or flag tag after it.
        emojiPattern = Pattern.compile(
                "(?:" + base + "|" + keycap + ")(?:" + base + "|[\\x{FE0F}\\x{200D}\\x{20E3}])*"
        );

        if(isCustomFontEnabled){
            loadFont(FONT_FILE_NAME,false);
        }
        if(isCustomEmojiFontEnabled){
            loadFont(EMOJI_FONT_FILE_NAME,true);
            if (emojiTypeface == null) {
                loadBundledEmojiFont();
            }
        }
    }

    // Uses the font bundled at patch time until the user picks their own.
    private static void loadBundledEmojiFont() {
        try {
            File fontFile = new File(context.getFilesDir(), BUNDLED_EMOJI_FONT_FILE_NAME);
            long installTime = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;

            if (!fontFile.exists() || fontFile.lastModified() < installTime) {
                File tmpFile = new File(context.getFilesDir(), BUNDLED_EMOJI_FONT_FILE_NAME + ".tmp");
                try (InputStream in = context.getAssets().open(BUNDLED_EMOJI_FONT_ASSET);
                     OutputStream out = new FileOutputStream(tmpFile)) {
                    byte[] buffer = new byte[64 * 1024];
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                }
                if (!tmpFile.renameTo(fontFile)) {
                    tmpFile.delete();
                    return;
                }
            }

            emojiTypeface = Typeface.createFromFile(fontFile);
            PikoUtils.logger("Bundled emoji font loaded");
        } catch (java.io.FileNotFoundException e) {
            // No font was bundled.
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    public static void loadFont(String fontName,boolean isEmojiFont){
        File fontFile = new File(context.getFilesDir(), fontName);
        if (!fontFile.exists()) {
            PikoUtils.logger("Font not found: " + fontFile.getAbsolutePath());
        }else{

            Typeface typeface = Typeface.createFromFile(fontFile);
            if(isEmojiFont){
                emojiTypeface = typeface;
            }else{
                textTypeface = typeface;
            }
            PikoUtils.logger("Font loaded: " + fontFile.getAbsolutePath());
        }
    }

    public static void deleteFont(boolean isEmojiFont){
        String filename = isEmojiFont ? EMOJI_FONT_FILE_NAME : FONT_FILE_NAME;
        File fontFile = new File(context.getFilesDir(), filename);
        if (fontFile.exists()) {
            if (fontFile.delete()) {
                PikoUtils.toast(str("piko_pref_delete_font_success"));
            } else {
                PikoUtils.toast(str("piko_pref_delete_font_fail"));
            }
        } else {
            PikoUtils.toast(str("piko_pref_delete_font_warn"));
        }
    }

    public static Spannable process(CharSequence input) {
        if(input == null) return null;
        SpannableString ss = new SpannableString(input);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if(isCustomFontEnabled && textTypeface != null) {
                ss.setSpan(
                    new TypefaceSpan(textTypeface),
                    0,
                    input.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
            if(isCustomEmojiFontEnabled && emojiTypeface != null) {
                Matcher matcher = emojiPattern.matcher(input);

                while (matcher.find()) {
                    ss.setSpan(
                        new TypefaceSpan(emojiTypeface),
                        matcher.start(),
                        matcher.end(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            }
        }
        return ss;
    }


}