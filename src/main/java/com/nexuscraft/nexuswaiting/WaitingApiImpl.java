package com.nexuscraft.nexuswaiting;

import java.util.UUID;

/** The public {@link WaitingApi}, backed by the service. */
final class WaitingApiImpl implements WaitingApi {

    private final WaitingService service;

    WaitingApiImpl(WaitingService service) {
        this.service = service;
    }

    @Override
    public void set(UUID player, String key, String label, long readyAtMillis) {
        if (player == null || key == null) {
            return;
        }
        service.set(player, key, label, readyAtMillis);
    }

    @Override
    public void clear(UUID player, String key) {
        if (player == null || key == null) {
            return;
        }
        service.clear(player, key);
    }

    @Override
    public boolean has(UUID player, String key) {
        return player != null && key != null && service.has(player, key);
    }
}
