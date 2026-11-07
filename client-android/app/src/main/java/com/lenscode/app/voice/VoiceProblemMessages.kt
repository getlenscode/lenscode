package com.lenscode.app.voice

/** Voice commands are routed by an OpenRouter model; with no key on the bridge there is nothing to route them. */
fun commandKeyMissingMessage(setupUrl: String): String =
    "Voice commands need an OpenRouter API key. On your phone or PC, open $setupUrl and paste it " +
        "under \"OpenRouter key\" (or start the bridge with OPENROUTER_API_KEY set)."

/** A routing call that failed or returned nothing usable; the command is not run. */
fun commandRoutingFailedMessage(transcript: String, error: Throwable, setupUrl: String): String =
    "Couldn't run \"$transcript\": ${error.message ?: error.javaClass.simpleName}. " +
        "Check the bridge is running and its OpenRouter key and model are valid at $setupUrl."
