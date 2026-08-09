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
 * A wrapper around FragmentedMp4Extractor that handles seeking into fMP4 streams.
 * It detects if the stream is already aligned (has headers) or needs sniffing for the next 'moof' box.
 */
@OptIn(UnstableApi::class)
class RobustSeekableFmp4Extractor(
    private val knownDurationUs: Long,
    private val knownFileSizeBytes: Long
) : Extractor {

    private val fmp4Extractor = FragmentedMp4Extractor()
    
    private var isSyncing = false
    private var seekTimeUs = 0L

    private class TimestampOffsetTrackOutput(
        private val delegate: androidx.media3.extractor.TrackOutput,
        private val offsetProvider: () -> Long
    ) : androidx.media3.extractor.TrackOutput by delegate {
        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: androidx.media3.extractor.TrackOutput.CryptoData?
        ) {
            delegate.sampleMetadata(timeUs + offsetProvider(), flags, size, offset, cryptoData)
        }
    }

    private class TimestampOffsetExtractorOutput(
        private val delegate: ExtractorOutput,
        private val offsetProvider: () -> Long
    ) : ExtractorOutput by delegate {
        override fun track(id: Int, type: Int): androidx.media3.extractor.TrackOutput {
            return TimestampOffsetTrackOutput(delegate.track(id, type), offsetProvider)
        }
    }

    override fun sniff(input: ExtractorInput): Boolean = fmp4Extractor.sniff(input)

    override fun init(output: ExtractorOutput) {
        val offsetOutput = TimestampOffsetExtractorOutput(output) { seekTimeUs }
        fmp4Extractor.init(object : ExtractorOutput by offsetOutput {
            override fun seekMap(seekMap: SeekMap) {
                if (knownDurationUs > 0 && knownFileSizeBytes > 0) {
                    val bitrate = ((knownFileSizeBytes * 8L * 1_000_000L) / knownDurationUs).toInt()
                    output.seekMap(ConstantBitrateSeekMap(knownFileSizeBytes, 0, bitrate, C.LENGTH_UNSET))
                } else {
                    output.seekMap(seekMap)
                }
            }
        })
    }

    override fun seek(position: Long, timeUs: Long) {
        // We always start in syncing mode on a new seek to verify the stream content
        isSyncing = position > 0 
        seekTimeUs = timeUs
        fmp4Extractor.seek(0, timeUs)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (isSyncing) {
            // Peek at the first 8 bytes to see if the server sent an fMP4 header (ftyp)
            val header = ByteArray(8)
            input.peekFully(header, 0, 8)
            
            // Box type is at offset 4 (e.g., "ftyp", "moov", "moof")
            val boxType = String(header, 4, 4)
            android.util.Log.d("RobustExtractor", "Peeked box type: $boxType at position ${input.position}")

            if (boxType == "ftyp" || boxType == "moov" || boxType == "moof") {
                // The stream is already valid or perfectly aligned! 
                // Stop syncing and let the underlying extractor handle the headers.
                isSyncing = false
                input.resetPeekPosition()
                android.util.Log.i("RobustExtractor", "Stream aligned at $boxType. Resuming.")
            } else {
                // We landed in the middle of data (likely 'mdat').
                // Sniff forward for the next Movie Fragment ('moof')
                input.resetPeekPosition()
                var matchState = 0
                var peekOffset = 0
                val b = ByteArray(1)
                
                while (isSyncing) {
                    if (!input.peekFully(b, 0, 1, true)) return Extractor.RESULT_END_OF_INPUT
                    peekOffset++
                    
                    val byte = b[0]
                    when (matchState) {
                        0 -> if (byte == 0x6D.toByte()) matchState = 1 // 'm'
                        1 -> matchState = if (byte == 0x6F.toByte()) 2 else if (byte == 0x6D.toByte()) 1 else 0 // 'o'
                        2 -> matchState = if (byte == 0x6F.toByte()) 3 else if (byte == 0x6D.toByte()) 1 else 0 // 'o'
                        3 -> if (byte == 0x66.toByte()) { // 'f'
                            // Found 'moof'! 
                            isSyncing = false
                            val skipCount = (peekOffset - 8).coerceAtLeast(0)
                            input.resetPeekPosition()
                            input.skipFully(skipCount)
                            android.util.Log.i("RobustExtractor", "Synced to moof after skipping $skipCount bytes. Resuming.")
                        } else {
                            matchState = if (byte == 0x6D.toByte()) 1 else 0
                        }
                    }
                    
                    // Safety break if we peek too far without finding a fragment
                    if (peekOffset > 1024 * 1024) {
                        android.util.Log.e("RobustExtractor", "Failed to find moof after 1MB. Giving up.")
                        isSyncing = false
                        input.resetPeekPosition()
                    }
                }
            }
        }
        
        return fmp4Extractor.read(input, seekPosition)
    }

    override fun release() {
        fmp4Extractor.release()
    }
}
