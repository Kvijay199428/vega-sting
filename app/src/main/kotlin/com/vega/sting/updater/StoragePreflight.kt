package com.vega.sting.updater

import android.os.StatFs
import java.io.File

/**
 * Free-space checks performed before and during an APK download.
 *
 * The margin is deliberately generous: an APK download that fills the volume to
 * the last byte would also break the recording feature that shares that volume.
 * The rule is
 *
 *     required = apkSize + max(20 MiB, 10% of apkSize)
 *
 * All members are pure so they can be unit-tested without an Android runtime.
 */
object StoragePreflight {

    /** Absolute floor for the safety reserve (20 MiB). */
    const val MIN_MARGIN_BYTES = 20L * 1024L * 1024L

    /** Conservative APK size assumed when the release reports none (60 MiB). */
    const val UNKNOWN_APK_ESTIMATE_BYTES = 60L * 1024L * 1024L

    /** The safety reserve for an APK of [apkSizeBytes]. */
    fun marginBytes(apkSizeBytes: Long): Long =
        maxOf(MIN_MARGIN_BYTES, apkSizeBytes / 10L)

    /**
     * Total free space required to download an APK of [apkSizeBytes]. A
     * non-positive size means "unknown"; a conservative estimate is used so the
     * preflight still refuses an obviously doomed download.
     */
    fun requiredBytes(apkSizeBytes: Long): Long {
        val size = if (apkSizeBytes > 0L) apkSizeBytes else UNKNOWN_APK_ESTIMATE_BYTES
        return size + marginBytes(size)
    }

    /** True when [availableBytes] can hold an APK of [apkSizeBytes] plus margin. */
    fun hasRoom(availableBytes: Long, apkSizeBytes: Long): Boolean =
        availableBytes >= requiredBytes(apkSizeBytes)

    /** Usable free bytes on the volume backing [dir], or -1 when unknown. */
    fun availableBytes(dir: File): Long = try {
        val stat = StatFs(dir.absolutePath)
        stat.availableBytes
    } catch (_: Exception) {
        -1L
    }
}
