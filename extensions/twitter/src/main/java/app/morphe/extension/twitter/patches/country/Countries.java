/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 *
 * Country matching and flags follow Control Panel for Twitter
 * (https://github.com/insin/control-panel-for-twitter), MIT License,
 * Copyright (c) 2019 Jonny Buchanan. The country name table is X-Posed's
 * (MIT License, Alexander Hagenah). Both licenses require this notice:
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files, to deal in the Software
 * without restriction, provided the copyright notice and this permission notice
 * are included in all copies or substantial portions of the Software.
 */

package app.morphe.extension.twitter.patches.country;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** "Account based in" countries: flags and the blocked-countries list. */
public final class Countries {
    private Countries() {}

    // "IN:india" is a regional-indicator flag; longer flags (England, Scotland, Wales) are spelled out.
    private static final String FLAG_DATA =
            "AD:andorra;AE:uae|united arab emirates;AF:afghanistan;AG:antigua and barbuda;AI:anguilla;AL:albania;AM:armenia;AO:angola;AQ:antarctica;AR:argentina;AS:american samoa;AT:austria;AU:australia;AW:aruba;AX:aland islands;AZ:azerbaijan;BA:bosnia|bosnia and herzegovina;BB:barbados;BD:bangladesh;BE:belgium;BF:burkina faso;BG:bulgaria;BH:bahrain;BI:burundi;BJ:benin;BL:saint barthelemy;BM:bermuda;"
            + "BN:brunei|brunei darussalam;BO:bolivia|bolivia (plurinational state of);BQ:bonaire|bonaire, sint eustatius and saba;BR:brazil;BS:bahamas;BT:bhutan;BV:bouvet island;BW:botswana;BY:belarus;BZ:belize;CA:canada;CC:cocos (keeling) islands;CD:congo-kinshasa|democratic republic of the congo|dr congo;CF:central african republic;CG:congo|congo-brazzaville|republic of the congo;CH:switzerland;"
            + "CI:ivoire|ivory coast;CK:cook islands;CL:chile;CM:cameroon;CN:china;CO:colombia;CR:costa rica;CU:cuba;CV:cabo verde|cape verde;CW:curacao|cura\u00e7ao;CX:christmas island;CY:cyprus;CZ:czech republic|czechia;DE:germany;DJ:djibouti;DK:denmark;DM:dominica;DO:dominican republic;DZ:algeria;EC:ecuador;EE:estonia;EG:egypt;EH:western sahara;ER:eritrea;ES:spain;ET:ethiopia;EU:europe|european union;FI:finland;"
            + "FJ:fiji;FK:falkland islands|falkland islands (malvinas);FM:federated states of micronesia|micronesia|micronesia (federated states of);FO:faroe islands;FR:france;GA:gabon;GB:britain|great britain|uk|united kingdom|united kingdom of great britain and northern ireland;GD:grenada;GE:georgia;GF:french guiana;GG:guernsey;GH:ghana;GI:gibraltar;GL:greenland;GM:gambia;GN:guinea;GP:guadeloupe;"
            + "GQ:equatorial guinea;GR:greece;GS:south georgia and the south sandwich islands;GT:guatemala;GU:guam;GW:guinea-bissau;GY:guyana;HK:china, hong kong special administrative region|hong kong;HM:heard island and mcdonald islands;HN:honduras;HR:croatia;HT:haiti;HU:hungary;ID:indonesia;IE:ireland;IL:israel;IM:isle of man;IN:india;IO:british indian ocean territory;IQ:iraq;"
            + "IR:iran|iran (islamic republic of);IS:iceland;IT:italy;JE:jersey;JM:jamaica;JO:jordan;JP:japan;KE:kenya;KG:kyrgyzstan;KH:cambodia;KI:kiribati;KM:comoros;KN:saint kitts and nevis;KP:north korea|s republic of korea;KR:korea|republic of korea|south korea;KW:kuwait;KY:cayman islands;KZ:kazakhstan;LA:laos|s democratic republic;LB:lebanon;LC:saint lucia;LI:liechtenstein;LK:sri lanka;LR:liberia;"
            + "LS:lesotho;LT:lithuania;LU:luxembourg;LV:latvia;LY:libya;MA:morocco;MC:monaco;MD:moldova|republic of moldova;ME:montenegro;MF:saint martin|saint martin (french part);MG:madagascar;MH:marshall islands;MK:macedonia|north macedonia;ML:mali;MM:burma|myanmar;MN:mongolia;MO:china, macao special administrative region|macao|macau;MP:northern mariana islands;MQ:martinique;MR:mauritania;MS:montserrat;"
            + "MT:malta;MU:mauritius;MV:maldives;MW:malawi;MX:mexico;MY:malaysia;MZ:mozambique;NA:namibia;NC:new caledonia;NE:niger;NF:norfolk island;NG:nigeria;NI:nicaragua;NL:netherlands|netherlands (kingdom of the);NO:norway;NP:nepal;NR:naoero|nauru;NU:niue;NZ:new zealand;OM:oman;PA:panama;PE:peru;PF:french polynesia;PG:papua new guinea;PH:philippines;PK:pakistan;PL:poland;PM:saint pierre and miquelon;"
            + "PN:pitcairn;PR:puerto rico;PS:palestine|state of palestine;PT:portugal;PW:palau;PY:paraguay;QA:qatar;RE:reunion|r\u00e9union;RO:romania;RS:serbia;RU:russia|russian federation;RW:rwanda;SA:saudi arabia;SB:solomon islands;SC:seychelles;SD:sudan;SE:sweden;SG:singapore;SH:saint helena;SI:slovenia;SJ:svalbard|svalbard and jan mayen|svalbard and jan mayen islands;SK:slovakia;SL:sierra leone;SM:san marino;"
            + "SN:senegal;SO:somalia;SR:suriname;SS:south sudan;ST:sao tome and principe;SV:el salvador;SX:sint maarten|sint maarten (dutch part);SY:syria|syrian arab republic;SZ:eswatini|swaziland;TC:turks and caicos islands;TD:chad;TF:french southern territories;TG:togo;TH:thailand;TJ:tajikistan;TK:tokelau;TL:east timor|timor-leste;TM:turkmenistan;TN:tunisia;TO:tonga;TR:turkey|t\u00fcrkiye;TT:trinidad and tobago;"
            + "TV:tuvalu;TW:taiwan;TZ:tanzania|united republic of tanzania;UA:ukraine;UG:uganda;UM:united states minor outlying islands;US:united states|united states of america|us|usa;UY:uruguay;UZ:uzbekistan;VA:holy see|vatican city;VC:saint vincent and the grenadines;VE:venezuela|venezuela (bolivarian republic of);VG:british virgin islands;VI:u.s. virgin islands|united states virgin islands|us virgin islands;"
            + "VN:viet nam|vietnam;VU:vanuatu;WF:wallis and futuna|wallis and futuna islands;WS:samoa;XK:kosovo;YE:yemen;YT:mayotte;ZA:south africa;ZM:zambia;ZW:zimbabwe;\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc65\udb40\udc6e\udb40\udc67\udb40\udc7f:england;\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc73\udb40\udc63\udb40\udc74\udb40\udc7f:scotland;\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f:wales";

    private static final Map<String, String> FLAGS = new HashMap<>();

    // Accounts that hide their country show a region instead ("South Asia", "West Asia",
    // "Eastern Europe (Non-EU)", "East Asia & Pacific"…). Each region falls into one kind, which
    // picks its icon and is what the blocked list names ("west asia", "africa").
    public static final String EU = "europe", LATIN_AMERICA = "latin america", NORTH_AMERICA = "north america",
            SOUTHEAST_ASIA = "southeast asia", SOUTH_ASIA = "south asia", CENTRAL_ASIA = "central asia",
            NORTH_ASIA = "north asia", WEST_ASIA = "west asia", EAST_ASIA = "east asia", AFRICA = "africa",
            OTHER_REGION = "region";
    private static final Pattern REGION_WORD = Pattern.compile(
            "\\b(?:asia|europe|eu|africa|america|americas|caribbean|australasia|oceania|pacific|middle east)\\b");
    private static final Map<String, String> REGION_KINDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Pattern MARKS = Pattern.compile("\\p{M}");

    static {
        for (String part : FLAG_DATA.split(";")) {
            int i = part.indexOf(':');
            String code = part.substring(0, i);
            String flag = code.matches("[A-Z]{2}") ? regionalIndicator(code) : code;
            for (String name : part.substring(i + 1).split("\\|")) FLAGS.put(key(name), flag);
        }
    }

    private static String regionalIndicator(String code) {
        StringBuilder sb = new StringBuilder();
        for (char c : code.toCharArray()) sb.appendCodePoint(0x1F1E6 + c - 'A');
        return sb.toString();
    }

    /** Lookup key: accents removed, curly apostrophes straightened, trimmed, lowercased, spaces collapsed. */
    static String key(String name) {
        String s = MARKS.matcher(Normalizer.normalize(String.valueOf(name), Normalizer.Form.NFKD)).replaceAll("");
        s = s.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u02bc', '\'');
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String stripVariationSelectors(String s) {
        return s == null ? "" : s.replace("\uFE0F", "");
    }

    public static boolean isRegion(String country) {
        return regionKind(country) != null;
    }

    /**
     * The kind of region X shows for an account that hides its country, or null for a country.
     * Cached, since posts ask for it on every bind.
     */
    public static String regionKind(String country) {
        if (country == null || country.isEmpty()) return null;
        // Keyed by the stored text: posts ask on every bind, so normalizing is only done once.
        String kind = REGION_KINDS.get(country);
        if (kind != null) return kind.isEmpty() ? null : kind;
        kind = classify(key(country));
        if (REGION_KINDS.size() > 512) REGION_KINDS.clear();
        REGION_KINDS.put(country, kind == null ? "" : kind);
        return kind;
    }

    private static String classify(String k) {
        if (FLAGS.containsKey(k) || !REGION_WORD.matcher(k).find()) return null;
        if (k.contains("africa")) return AFRICA; // before "middle east & north africa" counts as Asia
        if (k.contains("middle east") || k.matches(".*\\b(?:west|western|southwest|south-west) asia.*")) return WEST_ASIA;
        if (k.contains("europe") || k.matches("(?:the )?eu\\b.*")) return EU;
        if (k.contains("latin") || k.contains("caribbean") || k.contains("south america") || k.contains("central america")) return LATIN_AMERICA;
        if (k.contains("america")) return NORTH_AMERICA;
        if (k.matches(".*\\b(?:southeast|south-east|south east) asia.*")) return SOUTHEAST_ASIA;
        if (k.matches(".*\\b(?:south|southern) asia.*")) return SOUTH_ASIA;
        if (k.matches(".*\\b(?:central|middle) asia.*")) return CENTRAL_ASIA;
        if (k.matches(".*\\b(?:north|northern) asia.*")) return NORTH_ASIA;
        if (k.contains("asia") || k.contains("pacific") || k.contains("oceania") || k.contains("australasia")) return EAST_ASIA;
        return OTHER_REGION;
    }

    /** The flag emoji for a country (or the EU flag for a European region), or null. Other regions get an icon (RegionIcons). */
    public static String flagFor(String country) {
        if (country == null || country.isEmpty()) return null;
        String flag = FLAG_CACHE.get(country);
        if (flag != null) return flag.isEmpty() ? null : flag;
        flag = FLAGS.get(key(country));
        if (flag == null && EU.equals(regionKind(country))) flag = "\uD83C\uDDEA\uD83C\uDDFA";
        if (FLAG_CACHE.size() > 512) FLAG_CACHE.clear();
        FLAG_CACHE.put(country, flag == null ? "" : flag);
        return flag;
    }

    private static final Map<String, String> FLAG_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** The blocked-countries setting: comma separated, trimmed, lowercased, variation selectors removed. */
    public static List<String> parseList(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) return list;
        for (String s : raw.split(",")) {
            s = stripVariationSelectors(s.trim().toLowerCase(Locale.ROOT));
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    /**
     * Whether a country is blocked. Like Control Panel, entries are country names; a flag emoji
     * entry (🇻🇦) matches the country that flag stands for, and a region kind ("west asia", "africa")
     * blocks every region of that kind.
     */
    private static final Map<String, Boolean> BLOCKED_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile List<String> blockedCacheList;

    public static boolean isBlocked(String country, List<String> blocked) {
        if (country == null || country.isEmpty() || blocked.isEmpty()) return false;
        // Decisions per country, for the current list (a new list object means the setting changed).
        if (blockedCacheList != blocked) {
            BLOCKED_CACHE.clear();
            blockedCacheList = blocked;
        }
        Boolean cached = BLOCKED_CACHE.get(country);
        if (cached != null) return cached;
        boolean result = decideBlocked(country, blocked);
        if (BLOCKED_CACHE.size() > 512) BLOCKED_CACHE.clear();
        BLOCKED_CACHE.put(country, result);
        return result;
    }

    private static boolean decideBlocked(String country, List<String> blocked) {
        String c = stripVariationSelectors(country).trim().toLowerCase(Locale.ROOT);
        if (blocked.contains(c)) return true;
        String flag = flagFor(country);
        if (flag != null && blocked.contains(stripVariationSelectors(flag))) return true;
        String kind = regionKind(country);
        if (kind == null) return false;
        if (blocked.contains(kind)) return true;
        // "any region" (older setting) blocks hidden-country regions except Europe and the Americas.
        return blocked.contains("any region") && !EU.equals(kind) && !LATIN_AMERICA.equals(kind) && !NORTH_AMERICA.equals(kind);
    }
}
