package com.crm.resource.domain.service;

/** Attempt independent cleanup steps before reporting their combined failure. */
final class PeerCleanup {
    private PeerCleanup() { }

    static void run(Runnable first, Runnable second) {
        RuntimeException failure = null;
        for (Runnable action : java.util.List.of(first, second)) {
            try { action.run(); }
            catch (RuntimeException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure != null) throw failure;
    }
}
