package com.vega.sting.camera

/**
 * How the two camera feeds are arranged inside the composite frame.
 *
 * [AUTO] resolves to [PORTRAIT_STACK] when the effective output orientation is
 * portrait and to [LANDSCAPE_SPLIT] when it is landscape. The resolved value,
 * never [AUTO], is what the compositor renders.
 */
enum class DualCameraLayout {
    AUTO,
    PORTRAIT_STACK,
    LANDSCAPE_SPLIT;

    /** Resolves [AUTO] against the effective output orientation. */
    fun resolve(portraitOutput: Boolean): DualCameraLayout = when (this) {
        AUTO -> if (portraitOutput) PORTRAIT_STACK else LANDSCAPE_SPLIT
        else -> this
    }

    val label: String
        get() = when (this) {
            AUTO -> "AUTO"
            PORTRAIT_STACK -> "PORTRAIT STACK"
            LANDSCAPE_SPLIT -> "LANDSCAPE SPLIT"
        }

    companion object {
        fun fromStored(value: String?): DualCameraLayout =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/**
 * How a single feed is fitted into its assigned region. Never stretches the
 * image: the source aspect ratio is always preserved.
 */
enum class DualCameraScalingMode {
    /** Fill the whole region, cropping the excess image content. */
    CROP_TO_FILL,

    /** Show the whole image, letterboxing/pillarboxing the region. */
    FIT_WITH_BARS;

    val label: String
        get() = when (this) {
            CROP_TO_FILL -> "CROP TO FILL"
            FIT_WITH_BARS -> "FIT WITH BARS"
        }

    companion object {
        fun fromStored(value: String?): DualCameraScalingMode =
            entries.firstOrNull { it.name == value } ?: CROP_TO_FILL
    }
}

/** The three allowed split ratios (the first spatial region's fraction). */
enum class DualCameraSplitRatio(val firstFraction: Float) {
    RATIO_40_60(0.4f),
    RATIO_50_50(0.5f),
    RATIO_60_40(0.6f);

    val label: String
        get() = when (this) {
            RATIO_40_60 -> "40:60"
            RATIO_50_50 -> "50:50"
            RATIO_60_40 -> "60:40"
        }

    companion object {
        fun fromFraction(fraction: Float): DualCameraSplitRatio =
            entries.minByOrNull { kotlin.math.abs(it.firstFraction - fraction) } ?: RATIO_50_50

        fun fromStored(value: String?): DualCameraSplitRatio =
            entries.firstOrNull { it.name == value } ?: RATIO_50_50
    }
}

/** The composite output profiles offered for recording. */
enum class DualCameraQuality(val label: String, val shortSide: Int, val longSide: Int) {
    Q_720P("720P", 720, 1280),
    Q_1080P("1080P", 1080, 1920),
    Q_4K("4K", 2160, 3840);

    /** Portrait-oriented composite dimensions. */
    val portraitWidth: Int get() = shortSide
    val portraitHeight: Int get() = longSide

    /** Landscape-oriented composite dimensions. */
    val landscapeWidth: Int get() = longSide
    val landscapeHeight: Int get() = shortSide

    fun width(portrait: Boolean): Int = if (portrait) portraitWidth else landscapeWidth
    fun height(portrait: Boolean): Int = if (portrait) portraitHeight else landscapeHeight

    companion object {
        fun fromStored(value: String?): DualCameraQuality =
            entries.firstOrNull { it.name == value } ?: Q_720P

        fun fromName(value: String?): DualCameraQuality =
            entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: Q_720P
    }
}

/**
 * The single source of truth for the dual-camera feature. One instance drives
 * the Settings screen, the live preview and the recording pipeline; nothing
 * keeps a private copy of a subset of these fields.
 *
 * This model is deliberately free of Android dependencies so it can be unit
 * tested on the JVM.
 */
data class DualCameraConfig(
    val enabled: Boolean = false,
    val layout: DualCameraLayout = DualCameraLayout.AUTO,
    val frontOnTop: Boolean = true,
    val frontOnLeft: Boolean = true,
    val splitRatio: DualCameraSplitRatio = DualCameraSplitRatio.RATIO_50_50,
    val quality: DualCameraQuality = DualCameraQuality.Q_720P,
    val fps: Int = 30,
    val mirrorFront: Boolean = false,
    val scalingMode: DualCameraScalingMode = DualCameraScalingMode.CROP_TO_FILL
) {
    val splitFraction: Float get() = splitRatio.firstFraction

    /** Returns a copy whose values pass the documented bounds. */
    fun sanitized(): DualCameraConfig {
        val safeFps = when {
            fps >= 60 -> 60
            fps >= 30 -> 30
            fps >= 24 -> 24
            else -> 30
        }
        return copy(fps = safeFps)
    }

    companion object {
        /** The documented defaults, defined exactly once. */
        val DEFAULTS = DualCameraConfig()

        /** The FPS choices the feature can offer once capability-filtered. */
        val FPS_OPTIONS = listOf(24, 30, 60)

        /** Rebuilds a config from persisted, possibly corrupt, values. */
        fun fromStored(
            enabled: Boolean?,
            layout: String?,
            frontOnTop: Boolean?,
            frontOnLeft: Boolean?,
            splitRatio: String?,
            quality: String?,
            fps: Int?,
            mirrorFront: Boolean?,
            scalingMode: String?
        ): DualCameraConfig = DualCameraConfig(
            enabled = enabled ?: DEFAULTS.enabled,
            layout = DualCameraLayout.fromStored(layout),
            frontOnTop = frontOnTop ?: DEFAULTS.frontOnTop,
            frontOnLeft = frontOnLeft ?: DEFAULTS.frontOnLeft,
            splitRatio = DualCameraSplitRatio.fromStored(splitRatio),
            quality = DualCameraQuality.fromStored(quality),
            fps = fps ?: DEFAULTS.fps,
            mirrorFront = mirrorFront ?: DEFAULTS.mirrorFront,
            scalingMode = DualCameraScalingMode.fromStored(scalingMode)
        ).sanitized()

        /** Cycles to the next value, used by the Settings tap-to-change rows. */
        fun nextLayout(current: DualCameraLayout): DualCameraLayout =
            DualCameraLayout.entries[(current.ordinal + 1) % DualCameraLayout.entries.size]

        fun nextScaling(current: DualCameraScalingMode): DualCameraScalingMode =
            DualCameraScalingMode.entries[(current.ordinal + 1) % DualCameraScalingMode.entries.size]

        fun nextQuality(current: DualCameraQuality): DualCameraQuality =
            DualCameraQuality.entries[(current.ordinal + 1) % DualCameraQuality.entries.size]
    }
}
