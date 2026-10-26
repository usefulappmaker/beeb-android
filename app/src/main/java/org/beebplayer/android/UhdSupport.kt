// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.view.Display

/** Capability gate, not a claim that the TV is currently outputting HDR. */
data class UhdSupport(val supported: Boolean, val description: String) {
    companion object {
        fun inspect(context: Context): UhdSupport = runCatching {
            val display=context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val hlg=display?.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HLG)==true
            val main10=MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
                !codec.isEncoder && codec.supportedTypes.any { it.equals("video/hevc",true) } && runCatching {
                    val c=codec.getCapabilitiesForType("video/hevc")
                    c.profileLevels.any { it.profile==MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 || it.profile==MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 } && c.videoCapabilities?.isSizeSupported(3840,2160)==true
                }.getOrDefault(false)
            }
            when {
                !hlg -> UhdSupport(false,"Full HD fallback: Android does not report HLG support for the connected display/output. HDR10 or Dolby Vision support alone is not HLG support.")
                !main10 -> UhdSupport(false,"Full HD fallback: no compatible 4K HEVC Main 10 decoder was reported by Android.")
                else -> UhdSupport(true,"This device reports 4K HEVC Main 10 and an HLG display path. Actual quality depends on the programme, connection and decoder; check the TV’s HDR status during playback.")
            }
        }.getOrElse { UhdSupport(false,"Full HD fallback: Android could not report UHD decoder/display capabilities.") }
    }
}
