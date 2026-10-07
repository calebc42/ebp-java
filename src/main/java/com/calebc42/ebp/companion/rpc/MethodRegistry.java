// SPDX-License-Identifier: GPL-3.0-or-later
// The SPEC 11 method registry: sender, class, and legal states per method.
// Mirrors ebp/contract.json `methods`; RegistryDriftTest pins the two
// together so registry drift is a test failure, not a runtime surprise.
package com.calebc42.ebp.companion.rpc;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class MethodRegistry {
    private MethodRegistry() {}

    public enum Sender { EMACS, COMPANION, EITHER }

    public static final class Spec {
        public final Sender sender;
        public final boolean isRequest;
        public final Set<SessionState> states;
        Spec(Sender sender, boolean isRequest, Set<SessionState> states) {
            this.sender = sender; this.isRequest = isRequest; this.states = states;
        }
    }

    private static final Set<SessionState> SR =
        Collections.unmodifiableSet(EnumSet.of(SessionState.SYNCING, SessionState.READY));
    private static final Set<SessionState> R =
        Collections.unmodifiableSet(EnumSet.of(SessionState.READY));

    private static final Map<String, Spec> REGISTRY = new HashMap<String, Spec>();

    private static void reg(String name, Sender sender, boolean isRequest, Set<SessionState> states) {
        REGISTRY.put(name, new Spec(sender, isRequest, states));
    }

    static {
        reg("session.hello", Sender.EMACS, true, Collections.unmodifiableSet(EnumSet.of(SessionState.CONNECTED)));
        reg("auth.response", Sender.EMACS, true, Collections.unmodifiableSet(EnumSet.of(SessionState.CHALLENGED)));
        reg("session.ready", Sender.EMACS, true, Collections.unmodifiableSet(EnumSet.of(SessionState.SYNCING)));
        reg("session.superseded", Sender.COMPANION, false, SR);
        reg("surface.update", Sender.EMACS, true, SR);
        reg("surface.remove", Sender.EMACS, true, SR);
        reg("surface.release", Sender.EMACS, true, R);
        // SPEC 13.8.4 (#194): a data patch; this Companion never grants data.model.
        reg("surface.data", Sender.EMACS, true, SR);
        reg("queue.replay", Sender.EMACS, true, SR);
        reg("event.action", Sender.COMPANION, true, SR);
        reg("state.changed", Sender.COMPANION, false, R);
        reg("window.changed", Sender.COMPANION, false, SR);
        // SPEC 20.1.2 (#197): device locale advice, mirrored in the welcome.
        reg("locale.changed", Sender.COMPANION, false, SR);
        reg("dialog.show", Sender.EMACS, true, R);
        reg("snackbar.show", Sender.EMACS, true, R);
        reg("toast.show", Sender.EMACS, false, R);
        reg("pie_menu.show", Sender.EMACS, false, R);
        reg("pie_menu.dismiss", Sender.EMACS, false, R);
        reg("theme.set", Sender.EMACS, false, SR);
        reg("reminders.set", Sender.EMACS, true, SR);
        reg("edit.open", Sender.COMPANION, false, R);
        reg("edit.delta", Sender.COMPANION, false, R);
        reg("edit.caret", Sender.COMPANION, false, R);
        reg("edit.close", Sender.COMPANION, false, R);
        reg("edit.complete", Sender.COMPANION, true, R);
        reg("edit.candidate.doc", Sender.COMPANION, true, R);
        reg("edit.resync", Sender.EMACS, true, R);
        reg("edit.apply", Sender.EMACS, true, R);
        reg("diagnostics.show", Sender.EMACS, false, R);
        reg("eldoc.show", Sender.EMACS, false, R);
        reg("fontify.show", Sender.EMACS, false, R);
        reg("capability.invoke", Sender.EMACS, true, R);
        reg("triggers.set", Sender.EMACS, true, SR);
        reg("log.error", Sender.EITHER, false, SR);
        reg("rpc.cancel", Sender.EITHER, false, SR);
    }

    public static Spec lookup(String method) { return REGISTRY.get(method); }

    public static Set<String> methods() { return Collections.unmodifiableSet(REGISTRY.keySet()); }
}
