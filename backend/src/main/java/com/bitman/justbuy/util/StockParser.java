package com.bitman.justbuy.util;

import com.bitman.justbuy.dto.StockPick;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StockParser {

    private StockParser() {}

    static final Map<String, String> KNOWN_STOCKS;
    static {
        var m = new LinkedHashMap<String, String>();
        m.put("\uc0bc\uc131\uc804\uc790", "005930"); m.put("\uc0bc\uc131\uc804\uc790\uc6b0", "005935");
        m.put("SK\ud558\uc774\ub2c9\uc2a4", "000660"); m.put("\ud558\uc774\ub2c9\uc2a4", "000660");
        m.put("LG\uc5d0\ub108\uc9c0\uc194\ub8e8\uc158", "373220"); m.put("LG\uc5d0\ub108\uc9c0", "373220");
        m.put("\uc0bc\uc131\ubc14\uc774\uc624\ub85c\uc9c1\uc2a4", "207940"); m.put("\uc0bc\uc131\ubc14\uc774\uc624", "207940");
        m.put("\ud604\ub300\ucc28", "005380"); m.put("\ud604\ub300\uc790\ub3d9\ucc28", "005380");
        m.put("\uae30\uc544", "000270"); m.put("\uae30\uc544\ucc28", "000270");
        m.put("\uc140\ud2b8\ub9ac\uc628", "068270");
        m.put("POSCO\ud640\ub529\uc2a4", "005490"); m.put("\ud3ec\uc2a4\ucf54\ud640\ub529\uc2a4", "005490"); m.put("POSCO", "005490");
        m.put("KB\uae08\uc735", "105560");
        m.put("\uc2e0\ud55c\uc9c0\uc8fc", "055550");
        m.put("NAVER", "035420"); m.put("\ub124\uc774\ubc84", "035420");
        m.put("\uce74\uce74\uc624", "035720");
        m.put("LG\ud654\ud559", "051910");
        m.put("\uc0bc\uc131SDI", "006400");
        m.put("\ud604\ub300\ubaa8\ube44\uc2a4", "012330");
        m.put("\ud55c\uad6d\uc804\ub825", "015760"); m.put("\ud55c\uc804", "015760");
        m.put("SK\uc774\ub178\ubca0\uc774\uc158", "096770");
        m.put("\uc0bc\uc131\ubb3c\uc0b0", "028260");
        m.put("SK\ud154\ub808\ucf64", "017670"); m.put("SKT", "017670");
        m.put("KT", "030200");
        m.put("LG\uc804\uc790", "066570");
        m.put("\ud55c\uad6d\uc870\uc120\ud574\uc591", "009540"); m.put("HD\ud55c\uad6d\uc870\uc120\ud574\uc591", "009540");
        m.put("HD\ud604\ub300\uc911\uacf5\uc5c5", "329180");
        m.put("\uce74\uce74\uc624\ubc45\ud06c", "323410");
        m.put("\ud06c\ub798\ud504\ud1a4", "259960");
        m.put("\uc5d0\ucf54\ud504\ub85c\ube44\uc5e0", "247540"); m.put("\uc5d0\ucf54\ud504\ub85c", "086520");
        m.put("\ub450\uc0b0\uc5d0\ub108\ube4c\ub9ac\ud2f0", "034020");
        m.put("\ud55c\ud654\uc5d0\uc5b4\ub85c\uc2a4\ud398\uc774\uc2a4", "012450"); m.put("\ud55c\ud654\uc5d0\uc5b4\ub85c", "012450");
        m.put("\ud604\ub300\uc911\uacf5\uc5c5", "329180");
        m.put("\ud3ec\uc2a4\ucf54\ud4e8\uccd0\uc5e0", "003670");
        m.put("HLB", "028300");
        m.put("\ud55c\ubbf8\ubc18\ub3c4\uccb4", "042700");
        m.put("\ub9ac\ub178\uacf5\uc5c5", "058470");
        m.put("\uc774\uc218\ud398\ud0c0\uc2dc\uc2a4", "007660");
        m.put("\ucf54\uc2a4\ubaa8\uc2e0\uc18c\uc7ac", "005070");
        m.put("SK\uc2a4\ud018\uc5b4", "402340");
        m.put("\ud55c\ud654\uc194\ub8e8\uc158", "009830");
        m.put("\ub300\ud55c\ud56d\uacf5", "003490");
        m.put("\ud14c\ud06c\uc719", "089030");
        m.put("\uc0bc\uc131\uc804\uae30", "009150");
        m.put("\ub354\uc874\ubc84\uc988", "199820"); m.put("\ub354\uc874", "199820");
        KNOWN_STOCKS = Collections.unmodifiableMap(m);
    }

    private static final Pattern[] CODE_PATTERNS = {
        Pattern.compile("\uD83D\uDCCC\\s*([\\uAC00-\\uD7A3A-Za-z0-9\u00B7&\\s]{2,20}?)\\s*[\\(\\uFF08](\\d{6})[\\)\\uFF09]"),
        Pattern.compile("\\*\\*([\\uAC00-\\uD7A3A-Za-z0-9\u00B7&\\s]{2,20}?)\\s*[\\(\\uFF08](\\d{6})[\\)\\uFF09]\\*\\*"),
        // 영문 접두어 종목명: HD현대중공업, KB금융, SK하이닉스, LG에너지 등
        Pattern.compile("([A-Z]{1,6}[\\uAC00-\\uD7A3][\\uAC00-\\uD7A3A-Za-z0-9\u00B7&\\s]{0,15}?)\\s*[\\(\\uFF08](\\d{6})[\\)\\uFF09]"),
        Pattern.compile("([\\uAC00-\\uD7A3][\\uAC00-\\uD7A3A-Za-z0-9\u00B7&\\s]{1,15}?)\\s*[\\(\\uFF08](\\d{6})[\\)\\uFF09]"),
    };

    // 종목명 정제: 가격/숫자 접두어 제거 (예: "XXX원 테크윙" → "테크윙", "55,000원 삼성전자" → "삼성전자")
    private static final Pattern NAME_PRICE_PREFIX = Pattern.compile("^(?:[0-9,X]+\\s*원\\s*)+");
    // 종목명 정제: 후행 불필요 텍스트 제거
    private static final Pattern NAME_SUFFIX_NOISE = Pattern.compile("\\s+(?:등|의|은|는|이|가|을|를|에|도|로|과|와)$");

    private static final Pattern CURRENT_PRICE = Pattern.compile("\ud604\uc7ac\uac00\\s*(?:\uc57d\\s*)?([0-9,]+(?:~[0-9,]+)?)\\s*\uc6d0");
    private static final Pattern TARGET_PRICE = Pattern.compile("(?:\ubaa9\ud45c\uac00|1\ucc28\\s*\ubaa9\ud45c)[:\\s]*(?:\uc57d\\s*)?([0-9,]+(?:~[0-9,]+)?)\\s*\uc6d0");
    private static final Pattern STOP_LOSS = Pattern.compile("\uc190\uc808(?:\uac00)?[:\\s]*(?:\uc57d\\s*)?([0-9,]+(?:~[0-9,]+)?)\\s*\uc6d0");
    private static final Pattern ACTION_SELL = Pattern.compile("\ub9e4\ub3c4|\uc219|\ub9e4\ub3c4\\s*\ucd94\ucc9c|\ud558\ub77d\\s*\uc608\uc0c1");
    private static final Pattern ACTION_HOLD = Pattern.compile("\uad00\ub9dd|\ubcf4\ub958|\uc911\ub9bd");
    private static final Pattern ACTION_BUY = Pattern.compile("\ub9e4\uc218|\uc9c4\uc785|\ucd94\ucc9c|\uc720\ub9dd|\uc8fc\ubaa9|\uae0d\uc815|\uac15\uc138");
    private static final Pattern REASON = Pattern.compile("(?:\ub9e4\uc218\\s*\uadfc\uac70|\uadfc\uac70|\uc9c4\uc785\\s*\uadfc\uac70|\uc218\uae09\\s*\ud310\ub2e8|\ucd94\ucc9c\\s*\uc774\uc720|AI\\s*\ud569\uc758)[:\\s]*(.{10,80}?)(?:\\n|$)");
    private static final Pattern BULLET = Pattern.compile("-\\s+(.{10,60}?)(?:\\n|$)");
    private static final Pattern STOCK_CONTEXT = Pattern.compile("\uc6d0|\ub9e4\uc218|\ub9e4\ub3c4|\ucd94\ucc9c|\uc720\ub9dd|\uac15\uc138|\uc8fc\ubaa9|\uc9c4\uc785|\ubaa9\ud45c|\uc190\uc808");

    public static List<StockPick> parseStockPicks(String content) {
        if (content == null || content.isBlank()) return List.of();

        List<StockPick> picks = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Step 1: Patterns with explicit stock codes
        for (Pattern pattern : CODE_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String name = cleanStockName(matcher.group(1).trim());
                String code = matcher.group(2);
                if (name.isEmpty() || seen.contains(code)) continue;
                seen.add(code);

                String context = getContext(content, matcher.start(), 300);
                picks.add(new StockPick(name, code,
                    extractPrice(context, CURRENT_PRICE),
                    extractPrice(context, TARGET_PRICE),
                    extractPrice(context, STOP_LOSS),
                    extractAction(context),
                    extractReason(context)));
            }
        }

        // Step 2: Name-only matching from dictionary
        if (picks.size() < 3) {
            for (var entry : KNOWN_STOCKS.entrySet()) {
                String stockName = entry.getKey();
                String stockCode = entry.getValue();
                if (seen.contains(stockCode)) continue;

                int nameIdx = content.indexOf(stockName);
                if (nameIdx == -1) continue;

                int afterEnd = Math.min(content.length(), nameIdx + stockName.length() + 10);
                String afterName = content.substring(nameIdx + stockName.length(), afterEnd);
                if (afterName.matches("^\\s*[\\(\\uFF08]\\d{6}[\\)\\uFF09].*")) continue;

                seen.add(stockCode);
                String context = getContext(content, nameIdx, 250);

                if (STOCK_CONTEXT.matcher(context).find()) {
                    picks.add(new StockPick(stockName, stockCode,
                        extractPrice(context, CURRENT_PRICE),
                        extractPrice(context, TARGET_PRICE),
                        extractPrice(context, STOP_LOSS),
                        extractAction(context),
                        extractReason(context)));
                }
            }
        }

        return picks.size() <= 10 ? picks : picks.subList(0, 10);
    }

    /** 종목명에서 가격 접두어·접미 노이즈 제거 */
    private static String cleanStockName(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // "XXX원 테크윙" → "테크윙", "55,000원 삼성전자" → "삼성전자"
        String cleaned = NAME_PRICE_PREFIX.matcher(raw).replaceFirst("").trim();
        // 후행 조사 제거
        cleaned = NAME_SUFFIX_NOISE.matcher(cleaned).replaceFirst("").trim();
        // KNOWN_STOCKS에 있으면 정식 이름 반환
        for (var entry : KNOWN_STOCKS.entrySet()) {
            if (cleaned.contains(entry.getKey())) {
                return entry.getKey();
            }
        }
        return cleaned;
    }

    private static String getContext(String content, int idx, int range) {
        int end = Math.min(content.length(), idx + range);
        return content.substring(idx, end);
    }

    private static String extractPrice(String context, Pattern pattern) {
        Matcher m = pattern.matcher(context);
        return m.find() ? m.group(1) : null;
    }

    private static String extractAction(String context) {
        if (ACTION_SELL.matcher(context).find()) return "\ub9e4\ub3c4";
        if (ACTION_HOLD.matcher(context).find()) return "\uad00\ub9dd";
        if (ACTION_BUY.matcher(context).find()) return "\ub9e4\uc218";
        return "\uc8fc\ubaa9";
    }

    private static String extractReason(String context) {
        Matcher m = REASON.matcher(context);
        if (m.find()) return m.group(1).trim().replaceAll("\\*", "");
        Matcher b = BULLET.matcher(context);
        if (b.find()) return b.group(1).trim().replaceAll("\\*", "");
        return null;
    }
}
