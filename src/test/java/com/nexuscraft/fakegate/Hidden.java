package com.nexuscraft.fakegate;

import java.util.Set;
import java.util.UUID;

/** Stands in for NexusGate's own non-public pending-login list, which lives in a package the probe cannot see into. */
public final class Hidden {
    private Hidden() {
    }

    public static Object create(Set<UUID> ids) {
        return new Pending(ids);
    }

    static final class Pending {
        private final Set<UUID> ids;

        Pending(Set<UUID> ids) {
            this.ids = ids;
        }

        public boolean isPending(UUID id) {
            return ids.contains(id);
        }
    }
}
