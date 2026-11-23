package com.example.stock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

record AccountAccessCode(String value, byte[] hash) {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 8;
    private static final int LEGACY_LENGTH = 16;
    private static final Pattern COMPACT = Pattern.compile("[" + ALPHABET + "]+");
    private static final SecureRandom RANDOM = new SecureRandom();

    static AccountAccessCode issue() {
        var code = new StringBuilder();
        for (int index = 0; index < LENGTH; index++) {
            if (index != 0 && index % 4 == 0) code.append('-');
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        String value = code.toString();
        return new AccountAccessCode(value, hashOf(value));
    }

    static byte[] hashOf(String value) {
        if (value == null || value.length() > 64) return null;
        String compact = value.replaceAll("[-\\s]", "").toUpperCase(Locale.ROOT);
        if ((compact.length() != LENGTH && compact.length() != LEGACY_LENGTH)
                || !COMPACT.matcher(compact).matches()) return null;
        try {
            return MessageDigest.getInstance("SHA-256").digest(compact.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
