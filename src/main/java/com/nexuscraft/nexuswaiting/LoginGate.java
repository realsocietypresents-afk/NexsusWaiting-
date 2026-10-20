package com.nexuscraft.nexuswaiting;

import java.util.UUID;

/** Tells whether a player is still stuck at a login screen (so the menu waits until they are really in). */
interface LoginGate {
    boolean waiting(UUID id);

    LoginGate NONE = id -> false;
}
