package com.nexuscraft.nexuswaiting;

import java.util.function.Consumer;

/** Sends one JSON body somewhere and says (possibly later, possibly on another thread) whether it worked. */
interface Webhook {
    void post(String json, Consumer<Boolean> done);
}
