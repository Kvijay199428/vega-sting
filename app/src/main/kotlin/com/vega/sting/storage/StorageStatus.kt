package com.vega.sting.storage

/**
 * Coarse state of the recording destination, derived from measured free space
 * (or the inability to measure it).
 *
 * The ordering matters: [RESERVE_REACHED] is worse than [CRITICAL], which is
 * worse than [LOW]. Callers should treat [STORAGE_UNAVAILABLE] and
 * [STORAGE_ERROR] as "do not record" - an unverifiable volume must never be
 * interpreted as having unlimited space.
 */
enum class StorageStatus {
    /** Plenty of space; no warning. */
    NORMAL,

    /** Space is getting low; warn the user and restrict video. */
    LOW,

    /** Too little space to continue video safely; audio may still be possible. */
    CRITICAL,

    /** The configured hard reserve has been reached; stop all recording. */
    RESERVE_REACHED,

    /** The destination volume is missing or not writable. */
    STORAGE_UNAVAILABLE,

    /** Free space exists but could not be verified reliably. */
    STORAGE_ERROR;
}
