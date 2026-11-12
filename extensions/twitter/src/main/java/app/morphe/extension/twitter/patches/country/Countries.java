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
    private static final Map<String, String> REGION_GLOBES = new HashMap<>();

    // Accounts that hide their country show a region instead ("South Asia", "Eastern Europe (Non-EU)").
    private static final Pattern REGION = Pattern.compile(
            "^(?:(?:north|south|east|west|central|southeast|southwest|northeast|northwest|eastern|western|northern|southern|sub-saharan)\\s+)?"
                    + "(?:asia|europe|africa|america|americas|caribbean|australasia|oceania|middle east)(?:\\s*\\(.*\\))?$",
            Pattern.CASE_INSENSITIVE);
    // "any region" lets Europe and the Americas through.
    private static final Pattern REGION_OK = Pattern.compile("\\b(?:europe|americas?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKS = Pattern.compile("\\p{M}");

    static {
        for (String part : FLAG_DATA.split(";")) {
            int i = part.indexOf(':');
            String code = part.substring(0, i);
            String flag = code.matches("[A-Z]{2}") ? regionalIndicator(code) : code;
            for (String name : part.substring(i + 1).split("\\|")) FLAGS.put(key(name), flag);
        }
        String[][] globes = {
                {"africa", "\uD83C\uDF0D"}, {"asia", "\uD83C\uDF0F"}, {"australasia", "\uD83C\uDF0F"},
                {"caribbean", "\uD83C\uDF0E"}, {"central asia", "\uD83C\uDF0F"}, {"east asia", "\uD83C\uDF0F"},
                {"east asia & pacific", "\uD83C\uDF0F"}, {"eastern europe (non-eu)", "\uD83C\uDF0D"},
                {"europe", "\uD83C\uDF0D"}, {"north africa", "\uD83C\uDF0D"}, {"north america", "\uD83C\uDF0E"},
                {"oceania", "\uD83C\uDF0F"}, {"south america", "\uD83C\uDF0E"}, {"south asia", "\uD83C\uDF0F"},
                {"southeast asia", "\uD83C\uDF0F"}, {"west asia", "\uD83C\uDF0D"},
        };
        for (String[] g : globes) REGION_GLOBES.put(g[0], g[1]);
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
        return country != null && REGION.matcher(country).matches() && !"south africa".equalsIgnoreCase(country.trim());
    }

    /** The flag for a country (or a globe for a region), or null. */
    public static String flagFor(String country) {
        if (country == null || country.isEmpty()) return null;
        String k = key(country);
        String flag = FLAGS.get(k);
        if (flag == null) flag = REGION_GLOBES.get(k);
        if (flag == null && isRegion(country)) flag = "\uD83C\uDF10";
        return flag;
    }

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
     * entry (🇻🇦) matches the country that flag stands for, and "any region" blocks hidden-country
     * regions except Europe and the Americas.
     */
    public static boolean isBlocked(String country, List<String> blocked) {
        if (country == null || country.isEmpty() || blocked.isEmpty()) return false;
        String c = stripVariationSelectors(country).trim().toLowerCase(Locale.ROOT);
        if (blocked.contains(c)) return true;
        String flag = flagFor(country);
        if (flag != null && blocked.contains(stripVariationSelectors(flag))) return true;
        return blocked.contains("any region") && isRegion(c) && !REGION_OK.matcher(c).find();
    }
}
