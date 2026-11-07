package com.lenscode.app.voice

import kotlin.coroutines.cancellation.CancellationException

/** Works out a corrected address from the current one and what was said — [OpenRouterVoiceRouter] in the app. */
interface AddressEditor {
    suspend fun editAddress(current: String, spoken: String): Result<String>
}

/** What a spoken address edit came to. */
sealed class AddressEditOutcome {
    /** The new address to put in the field. */
    data class Set(val address: String) : AddressEditOutcome()

    /** A correction needs the model, and there is no OpenRouter key on the bridge. */
    data object NeedsKey : AddressEditOutcome()

    /** The model could not be reached, or did not return a usable address. The field is left alone. */
    data class Failed(val error: Throwable) : AddressEditOutcome()
}

/**
 * A whole new spoken address replaces [before] directly; anything else is a correction that [editor] (null
 * when there is no key) must work out. A failure is reported, never papered over with a guess.
 */
suspend fun resolveSpokenAddress(before: String, spoken: String, editor: AddressEditor?): AddressEditOutcome {
    if (replacesWholeAddress(before, spoken)) {
        return AddressEditOutcome.Set(applySpokenAddressEdit(before, spoken, paths = true))
    }
    val active = editor ?: return AddressEditOutcome.NeedsKey
    val result = try {
        active.editAddress(before, spoken)
    } catch (e: CancellationException) {
        throw e // structured concurrency: never swallow
    } catch (e: Exception) {
        Result.failure(e)
    }
    return result.fold({ AddressEditOutcome.Set(it) }, { AddressEditOutcome.Failed(it) })
}
