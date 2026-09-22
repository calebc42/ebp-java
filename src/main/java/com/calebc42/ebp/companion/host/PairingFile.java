// SPDX-License-Identifier: GPL-3.0-or-later
// Out-of-band test pairing provisioning (SPEC 9.1): one CSPRNG pairing ID
// and token generated on first use and kept in storage private to the
// Companion's application identity with the most restrictive mode the
// platform offers.  The token never crosses the wire and is never logged.
package com.calebc42.ebp.companion.host;

import com.calebc42.ebp.companion.auth.Auth;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

public final class PairingFile {
    public final String pairingId;
    public final String tokenDisplay;

    private PairingFile(String pairingId, String tokenDisplay) {
        this.pairingId = pairingId;
        this.tokenDisplay = tokenDisplay;
    }

    public byte[] token() { return Auth.decodePairingToken(tokenDisplay); }

    /** Load FILE, or create it with fresh credentials from RANDOM. */
    public static PairingFile loadOrCreate(File file, Auth.RandomSource random) throws IOException {
        if (file.exists()) {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Json.Obj o = (Json.Obj) JsonParser.parse(text);
            String id = o.string("pairing_id");
            String token = o.string("token");
            if (id == null || !Auth.isValidPairingId(id) || token == null)
                throw new IOException("malformed pairing file " + file);
            Auth.decodePairingToken(token);
            return new PairingFile(id, token);
        }
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String id = Auth.generateHex16(random);
        String token = Auth.encodePairingToken(raw);
        Json.Obj o = new Json.Obj().put("format", 1).put("pairing_id", id).put("token", token);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File temp = new File(dir, file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(Json.writeUtf8(o));
            out.getFD().sync();
        } finally {
            out.close();
        }
        try {
            Files.setPosixFilePermissions(temp.toPath(),
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platform: application-private storage is the floor.
        }
        Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new PairingFile(id, token);
    }
}
