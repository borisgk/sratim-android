package com.ru9n.sratim.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.mp4.FragmentedMp4Extractor

/**
 * A wrapper around FragmentedMp4Extractor that handles seeking into unindexed streams
 * by performing a forward-scan for the next 'moof' box signature.
 */
@OptIn(UnstableApi::class)
class RobustSeekableFmp4Extractor(
    private val knownDurationUs: Long,
    private val knownFileSizeBytes: Long
) : Extractor {

    private val fmp4Extractor = FragmentedMp4Extractor()
    
    // State management for our custom sync phase
    private var isSyncing = false
    private var seekTimeUs = 0L
    private val searchBuffer = ByteArray(32 * 1024) // 32KB rolling search window

    override fun sniff(input: ExtractorInput): Boolean = fmp4Extractor.sniff(input)

    override fun init(output: ExtractorOutput) {
        // Intercept the SeekMap injection using a wrapper
        fmp4Extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = output.track(id, type)
            override fun endTracks() = output.endTracks()
            
            override fun seekMap(seekMap: SeekMap) {
                if (knownDurationUs > 0 && knownFileSizeBytes > 0) {
                    val bitrate = ((knownFileSizeBytes * 8L * 1_000_000L) / knownDurationUs).toInt()
                    android.util.Log.d("RobustExtractor", "Injecting ConstantBitrateSeekMap: bitrate=$bitrate, size=$knownFileSizeBytes")
                    output.seekMap(ConstantBitrateSeekMap(knownFileSizeBytes, 0, bitrate, C.LENGTH_UNSET))
                } else {
                    android.util.Log.w("RobustExtractor", "Metadata missing! Falling back to default seekMap. duration=$knownDurationUs, size=$knownFileSizeBytes")
                    output.seekMap(seekMap)
                }
            }
        })
    }

    override fun seek(position: Long, timeUs: Long) {
        // Position 0 is the start of the file (ftyp/moov boxes), no syncing needed there.
        isSyncing = position > 0 
        seekTimeUs = timeUs
        fmp4Extractor.seek(position, timeUs)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (isSyncing) {
            android.util.Log.d("RobustExtractor", "Syncing... current pos: ${input.position}, seeking to: ${seekTimeUs}")
            var matchState = 0
            
            // Loop and consume bytes until we find the start of a new fragment
            while (true) {
                val bytesRead = input.read(searchBuffer, 0, searchBuffer.size)
                if (bytesRead == C.RESULT_END_OF_INPUT) {
                    return Extractor.RESULT_END_OF_INPUT
                }

                for (i in 0 until bytesRead) {
                    val b = searchBuffer[i]
                    // Look for the "moof" magic bytes: 0x6D, 0x6F, 0x6F, 0x66
                    when (matchState) {
                        0 -> if (b == 0x6D.toByte()) matchState = 1 // 'm'
                        1 -> matchState = if (b == 0x6F.toByte()) 2 else if (b == 0x6D.toByte()) 1 else 0 // 'o'
                        2 -> matchState = if (b == 0x6F.toByte()) 3 else if (b == 0x6D.toByte()) 1 else 0 // 'o'
                        3 -> if (b == 0x66.toByte()) { // 'f'
                            
                            // We found "moof"! Calculate its absolute file position.
                            val currentReadStart = input.position - bytesRead
                            val fBytePosition = currentReadStart + i
                            
                            // The 4-byte box size is located exactly 7 bytes before the 'f' byte.
                            val boxStartPosition = fBytePosition - 7
                            
                            if (boxStartPosition >= 0) {
                                isSyncing = false
                                
                                // Update the underlying extractor with the true aligned position
                                fmp4Extractor.seek(boxStartPosition, seekTimeUs)
                                
                                // Tell ExoPlayer to close the stream and reopen it exactly at our box
                                seekPosition.position = boxStartPosition
                                return Extractor.RESULT_SEEK
                            } else {
                                matchState = 0 // False positive too close to file start
                            }
                        } else {
                            matchState = if (b == 0x6D.toByte()) 1 else 0
                        }
                    }
                }
            }
        }
        
        // Once we are perfectly aligned at a moof box, let ExoPlayer do its job!
        return fmp4Extractor.read(input, seekPosition)
    }

    override fun release() {
        fmp4Extractor.release()
    }
}
