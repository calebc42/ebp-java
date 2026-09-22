// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.calebc42.ebp.companion.auth.Auth;

import org.junit.Test;

public class AuthTest {
    private static final String CN = "202122232425262728292a2b2c2d2e2f";
    private static final String SN = "303132333435363738393a3b3c3d3e3f";

    @Test public void knownAnswerVector() {
        // SPEC 9.3: normative KAT.
        assertEquals("a76f9e392582c990ef08858fe69740323499ab87566d9b4e6f99a246bdd024a6",
            Auth.clientProof(Fixtures.TOKEN, Fixtures.PAIRING_ID, CN, SN));
        assertEquals("ca1c37bcb735442fb979127a07fc41d2a15ea0fcf58ae1ffa4bf06edc0bdfdcc",
            Auth.serverProof(Fixtures.TOKEN, Fixtures.PAIRING_ID, CN, SN));
        assertTrue(Auth.verifyClientProof(Auth.clientProof(Fixtures.TOKEN, Fixtures.PAIRING_ID, CN, SN),
            Fixtures.TOKEN, Fixtures.PAIRING_ID, CN, SN));
        assertFalse(Auth.verifyClientProof(Auth.serverProof(Fixtures.TOKEN, Fixtures.PAIRING_ID, CN, SN),
            Fixtures.TOKEN, Fixtures.PAIRING_ID, CN, SN));
        assertFalse(Auth.verifyClientProof("not-hex", Fixtures.TOKEN, Fixtures.PAIRING_ID, CN, SN));
        assertFalse(Auth.verifyClientProof(null, Fixtures.TOKEN, Fixtures.PAIRING_ID, CN, SN));
    }

    @Test public void tokenCodec() {
        byte[] expected = new byte[16];
        for (int i = 0; i < 16; i++) expected[i] = (byte) i;
        assertArrayEquals(expected, Fixtures.TOKEN);
        assertEquals(Fixtures.TOKEN_DISPLAY, Auth.encodePairingToken(expected));
        try { Auth.decodePairingToken("AAECAwQFBgcICQoLDA0OD"); assertTrue(false); }
        catch (IllegalArgumentException ok) { /* wrong length */ }
        try { Auth.decodePairingToken("AAECAwQFBgcICQoLDA0OD+"); assertTrue(false); }
        catch (IllegalArgumentException ok) { /* not base64url */ }
    }

    @Test public void grammar() {
        assertTrue(Auth.isValidNonce(CN));
        assertFalse(Auth.isValidNonce(CN.toUpperCase()));
        assertFalse(Auth.isValidNonce(CN + "0"));
        assertTrue(Auth.isValidProof(Auth.clientProof(Fixtures.TOKEN, Fixtures.PAIRING_ID, CN, SN)));
        String n1 = Auth.generateNonce(), n2 = Auth.generateNonce();
        assertTrue(Auth.isValidNonce(n1));
        assertFalse(n1.equals(n2));
    }
}
