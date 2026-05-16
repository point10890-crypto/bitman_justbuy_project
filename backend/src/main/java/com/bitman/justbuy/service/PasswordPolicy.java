package com.bitman.justbuy.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 비밀번호 강도 정책 유틸.
 *
 * 규칙 (공개 배포 기준):
 *  - 최소 12자
 *  - 대문자 1개 이상
 *  - 소문자 1개 이상
 *  - 숫자 1개 이상
 *  - 특수문자 1개 이상
 *
 * DTO @Pattern 으로도 강제하지만, 서비스 계층에서도 isStrong() 으로 이중 체크한다.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;

    /** DTO 어노테이션용 정규식. 한 줄에 4개 긍정 전방탐색 + 12자 이상. */
    public static final String REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).{12,}$";

    public static final String MESSAGE =
            "비밀번호는 12자 이상이며 대문자, 소문자, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.";

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SPECIAL = "!@#$%^&*()-_=+[]{};:,.<>?";

    private PasswordPolicy() {}

    public static boolean isStrong(String pw) {
        if (pw == null || pw.length() < MIN_LENGTH) return false;
        boolean upper = false, lower = false, digit = false, special = false;
        for (int i = 0; i < pw.length(); i++) {
            char c = pw.charAt(i);
            if (Character.isUpperCase(c)) upper = true;
            else if (Character.isLowerCase(c)) lower = true;
            else if (Character.isDigit(c)) digit = true;
            else if (!Character.isWhitespace(c)) special = true;
        }
        return upper && lower && digit && special;
    }

    /**
     * 정책을 자동 만족하는 18자 랜덤 비밀번호 생성.
     * 관리자 계정 최초 부트스트랩 등에서 사용.
     */
    public static String generateStrong() {
        SecureRandom rnd = new SecureRandom();
        List<Character> chars = new ArrayList<>(18);
        chars.add(UPPER.charAt(rnd.nextInt(UPPER.length())));
        chars.add(LOWER.charAt(rnd.nextInt(LOWER.length())));
        chars.add(DIGIT.charAt(rnd.nextInt(DIGIT.length())));
        chars.add(SPECIAL.charAt(rnd.nextInt(SPECIAL.length())));

        String pool = UPPER + LOWER + DIGIT + SPECIAL;
        while (chars.size() < 18) {
            chars.add(pool.charAt(rnd.nextInt(pool.length())));
        }
        Collections.shuffle(chars, rnd);
        StringBuilder sb = new StringBuilder(18);
        for (char c : chars) sb.append(c);
        return sb.toString();
    }
}
