package com.ru9n.sratim.mobile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.ru9n.sratim.core.network.NetworkClient

class SratimApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { NetworkClient.getImageOkHttpClient(this) }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024L * 1024L)
                    .build()
            }
            .allowHardware(true)
            .allowRgb565(true)
            .crossfade(150)
            .build()
    }
}
