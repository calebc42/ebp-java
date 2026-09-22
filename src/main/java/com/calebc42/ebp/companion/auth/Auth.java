// SPDX-License-Identifier: GPL-3.0-or-later
// EBP 3 pairing and mutual authentication.  Implements ebp/SPEC.md section 9.
// Java 7: no java.util.Base64, so the RFC 4648 base64url token decoder is
// local; HMAC-SHA256 through javax.crypto.Mac; the constant-time comparison
// through MessageDigest.isEqual (non-short-circuiting since Java 6u17).
package com.calebc42.ebp.companion.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Auth {
    private Auth() {}

    public static final int PROTOCOL_VERSION = 3;

    /** Injectable source of cryptographically random octets. */
    public interface RandomSource {
        void nextBytes(byte[] out);
    }

    /** The platform CSPRNG (SPEC 9.2). */
    public static final RandomSource SECURE_RANDOM = new RandomSource() {
        private final SecureRandom random = new SecureRandom();
        @Override public void nextBytes(byte[] out) { random.nextBytes(out); }
    };

    /** SPEC 9.2/4.4: exactly 32 lowercase hexadecimal characters. */
    public static boolean isValidNonce(String s) { return isLowerHex(s, 32); }

    /** SPEC 4.4: exactly 64 lowercase hexadecimal characters. */
    public static boolean isValidProof(String s) { return isLowerHex(s, 64); }

    /** SPEC 9.1: a PairingId is exactly 32 lowercase hexadecimal characters. */
    public static boolean isValidPairingId(String s) { return isLowerHex(s, 32); }

    /** SPEC 4.4: an EventId is exactly 32 lowercase hexadecimal characters. */
    public static boolean isValidEventId(String s) { return isLowerHex(s, 32); }

    public static boolean isLowerHex(String s, int length) {
        if (s == null || s.length() != length) return false;
        for (int i = 0; i < length; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** Fresh 32-hex nonce (or EventId) from RANDOM. */
    public static String generateHex16(RandomSource random) {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        return toHex(raw);
    }

    public static String generateNonce() { return generateHex16(SECURE_RANDOM); }

    // ------------------------------------------------ pairing token (9.1)

    private static final String B64URL =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /** SPEC 9.1: the 22-character RFC 4648 base64url display form (padding
     * omitted) decodes to the 16 raw octets both endpoints use as HMAC key. */
    public static byte[] decodePairingToken(String display) {
        if (display == null || display.length() != 22)
            throw new IllegalArgumentException("pairing token must be 22 base64url characters");
        byte[] out = new byte[16];
        int bits = 0, acc = 0, o = 0;
        for (int i = 0; i < 22; i++) {
            int v = B64URL.indexOf(display.charAt(i));
            if (v < 0) throw new IllegalArgumentException("pairing token is not base64url");
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (o < 16) out[o++] = (byte) ((acc >> bits) & 0xFF);
            }
        }
        // 22 characters carry 132 bits; the trailing 4 bits MUST be zero for a
        // canonical encoding of 16 octets.
        if (o != 16 || (acc & ((1 << bits) - 1)) != 0)
            throw new IllegalArgumentException("pairing token did not decode to 16 octets");
        return out;
    }

    /** The display form of 16 raw token octets (SPEC 9.1). */
    public static String encodePairingToken(byte[] raw) {
        if (raw == null || raw.length != 16) throw new IllegalArgumentException("16 octets required");
        StringBuilder sb = new StringBuilder(22);
        int acc = 0, bits = 0;
        for (int i = 0; i < 16; i++) {
            acc = (acc << 8) | (raw[i] & 0xFF);
            bits += 8;
            while (bits >= 6) {
                bits -= 6;
                sb.append(B64URL.charAt((acc >> bits) & 0x3F));
            }
        }
        if (bits > 0) sb.append(B64URL.charAt((acc << (6 - bits)) & 0x3F));
        return sb.toString();
    }

    // ------------------------------------------------------- proofs (9.3)

    public static String clientProofMessage(String pairingId, String clientNonce, String serverNonce) {
        return "EBP/" + PROTOCOL_VERSION + " client:" + pairingId + ":" + clientNonce + ":" + serverNonce;
    }

    public static String serverProofMessage(String pairingId, String clientNonce, String serverNonce) {
        return "EBP/" + PROTOCOL_VERSION + " companion:" + pairingId + ":" + serverNonce + ":" + clientNonce;
    }

    /** SPEC 9.3 client proof over the exact ASCII concatenation. */
    public static String clientProof(byte[] token, String pairingId, String clientNonce, String serverNonce) {
        return toHex(hmacSha256(token, clientProofMessage(pairingId, clientNonce, serverNonce)));
    }

    /** SPEC 9.3 companion proof; note the swapped nonce order. */
    public static String serverProof(byte[] token, String pairingId, String clientNonce, String serverNonce) {
        return toHex(hmacSha256(token, serverProofMessage(pairingId, clientNonce, serverNonce)));
    }

    /** SPEC 9.2: the fixed dummy key the unknown-pairing-ID path verifies
     * against, so that branch does the same HMAC-SHA256 work as a known ID. */
    public static final byte[] DUMMY_KEY = new byte[16];

    /** SPEC 9.3: compare two fixed-length ASCII values without leaking how
     * many leading characters matched; null never matches. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** SPEC 9.3: constant-time comparison; malformed proofs never match. The
     * HMAC is always computed (also with DUMMY_KEY on the unknown path). */
    public static boolean verifyClientProof(String proof, byte[] token, String pairingId,
                                            String clientNonce, String serverNonce) {
        String expected = clientProof(token, pairingId, clientNonce, serverNonce);
        boolean wellFormed = isValidProof(proof);
        boolean equal = MessageDigest.isEqual(
            (wellFormed ? proof : expected).getBytes(StandardCharsets.US_ASCII),
            expected.getBytes(StandardCharsets.US_ASCII));
        return wellFormed & equal;
    }

    public static boolean verifyServerProof(String proof, byte[] token, String pairingId,
                                            String clientNonce, String serverNonce) {
        String expected = serverProof(token, pairingId, clientNonce, serverNonce);
        boolean wellFormed = isValidProof(proof);
        boolean equal = MessageDigest.isEqual(
            (wellFormed ? proof : expected).getBytes(StandardCharsets.US_ASCII),
            expected.getBytes(StandardCharsets.US_ASCII));
        return wellFormed & equal;
    }

    public static byte[] hmacSha256(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = HEX[(bytes[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
