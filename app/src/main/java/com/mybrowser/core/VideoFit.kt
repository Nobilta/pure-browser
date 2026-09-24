package com.mybrowser.core

/**
 * How the enhanced player fits the picture into the screen.
 *
 * These names travel: a chosen preset is stored per site and sent to the page as this constant's
 * name, so they are part of the stored format and of the probe protocol at once — renaming one
 * silently drops an existing site preference together with an in-flight command.
 */
enum class VideoFit {
    /** The picture keeps its own ratio and is letterboxed inside the screen. */
    NATURAL,

    /** The picture is fitted into a 3:4 box. */
    RATIO_3_4,

    /** The picture is fitted into a 16:9 box. */
    RATIO_16_9,

    /** The picture is cropped to the screen, so nothing is letterboxed away. */
    FILL,
}
