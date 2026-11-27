package dev.stsv.economy.secret.newcomer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** No model: his words are the scripted line. */
final class ScriptedMind implements NewcomerMind {

    @Override
    public boolean live() {
        return false;
    }

    @Override
    public CompletableFuture<String> think(Ask ask, Duration timeout) {
        return CompletableFuture.completedFuture(ask.scripted());
    }
}
