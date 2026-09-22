// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.store;

/**
 * Permanent storage SPI, partitioned by pairing identity.  A successful
 * {@link #write} result is returned only after commit; throwing from the
 * block, or a storage failure while committing it, rolls the entire
 * pairing-confined write back and surfaces as {@link StoreException}.
 *
 * Every persisted format carries a version and fails closed on an unknown
 * one; see the concrete implementations.
 */
public interface CompanionStore {

    public interface WriteBlock<T> {
        T apply(WriteTx tx) throws StoreException;
    }

    Snapshot load(String pairingId) throws StoreException;

    <T> T write(String pairingId, WriteBlock<T> block) throws StoreException;

    /** Erase every record of PAIRING-ID (SPEC 9.1 revocation), atomically. */
    void erasePairing(String pairingId) throws StoreException;

    void close();
}
