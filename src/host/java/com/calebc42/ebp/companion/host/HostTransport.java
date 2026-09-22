// SPDX-License-Identifier: GPL-3.0-or-later
// What the runner needs from whichever SPEC 5 transport it was started
// with: its profile name, its address for the status report, and a stop.
package com.calebc42.ebp.companion.host;

public interface HostTransport {
    /** The SPEC 5 profile identifier, e.g. "loopback-tcp". */
    String profile();
    /** The bound or dialed address: a port number or a socket path. */
    String address();
    void stop();
}
