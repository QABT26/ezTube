package com.qabt.eztube.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class NetworkType {
    WIFI,
    CELLULAR,
    OTHER,
    OFFLINE
}

enum class MobileDataPriority(val label: String) {
    DATA_SAVER("Data saver"),
    BALANCED("Balanced"),
    QUALITY("Quality")
}

data class DataUsageWindow(
    val wifiBytes: Long = 0L,
    val mobileBytes: Long = 0L,
    val otherBytes: Long = 0L
) {
    val totalBytes: Long get() = wifiBytes + mobileBytes + otherBytes
}

data class DataUsageSnapshot(
    val today: DataUsageWindow,
    val last7Days: DataUsageWindow,
    val last30Days: DataUsageWindow,
    val sinceReset: DataUsageWindow,
    val resetAtMs: Long
)

data class StorageSnapshot(
    val apkBytes: Long,
    val appDataBytes: Long,
    val cacheBytes: Long
) {
    val totalBytes: Long get() = apkBytes + appDataBytes + cacheBytes
}

object NetworkEnvironment {
    fun type(context: Context): NetworkType {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.OFFLINE
        val network = manager.activeNetwork ?: return NetworkType.OFFLINE
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkType.OFFLINE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.OTHER
            else -> NetworkType.OFFLINE
        }
    }

    fun label(type: NetworkType): String = when (type) {
        NetworkType.WIFI -> "Wi-Fi"
        NetworkType.CELLULAR -> "Mobile"
        NetworkType.OTHER -> "Network"
        NetworkType.OFFLINE -> "Offline"
    }
}

class NetworkUsageTracker(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("network_usage_v1", Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Synchronized
    fun sample(): DataUsageSnapshot {
        val currentUidBytes = uidBytes()
        val previousUidBytes = prefs.getLong(KEY_LAST_UID_BYTES, -1L)
        val delta = if (currentUidBytes >= 0L && previousUidBytes >= 0L) {
            (currentUidBytes - previousUidBytes).coerceAtLeast(0L)
        } else {
            0L
        }

        if (currentUidBytes >= 0L) {
            val editor = prefs.edit().putLong(KEY_LAST_UID_BYTES, currentUidBytes)
            if (prefs.getLong(KEY_RESET_AT, 0L) == 0L) {
                editor.putLong(KEY_RESET_AT, System.currentTimeMillis())
            }
            if (delta > 0L) {
                val type = NetworkEnvironment.type(appContext)
                val bucket = bucketPrefix(System.currentTimeMillis(), type)
                val totalKey = totalKey(type)
                editor
                    .putLong(bucket, prefs.getLong(bucket, 0L) + delta)
                    .putLong(totalKey, prefs.getLong(totalKey, 0L) + delta)
            }
            editor.apply()
        }

        return readSnapshot()
    }

    @Synchronized
    fun reset(): DataUsageSnapshot {
        val current = uidBytes()
        prefs.edit()
            .clear()
            .putLong(KEY_LAST_UID_BYTES, current.coerceAtLeast(0L))
            .putLong(KEY_RESET_AT, System.currentTimeMillis())
            .apply()
        return readSnapshot()
    }

    fun mobileBytesSinceReset(): Long = prefs.getLong(KEY_TOTAL_MOBILE, 0L)

    private fun readSnapshot(): DataUsageSnapshot {
        fun window(days: Int): DataUsageWindow {
            var wifi = 0L
            var mobile = 0L
            var other = 0L
            val now = System.currentTimeMillis()
            for (offset in 0 until days) {
                val day = dayFormat.format(Date(now - offset * DAY_MS))
                wifi += prefs.getLong("day_${day}_wifi", 0L)
                mobile += prefs.getLong("day_${day}_mobile", 0L)
                other += prefs.getLong("day_${day}_other", 0L)
            }
            return DataUsageWindow(wifi, mobile, other)
        }

        return DataUsageSnapshot(
            today = window(1),
            last7Days = window(7),
            last30Days = window(30),
            sinceReset = DataUsageWindow(
                wifiBytes = prefs.getLong(KEY_TOTAL_WIFI, 0L),
                mobileBytes = prefs.getLong(KEY_TOTAL_MOBILE, 0L),
                otherBytes = prefs.getLong(KEY_TOTAL_OTHER, 0L)
            ),
            resetAtMs = prefs.getLong(KEY_RESET_AT, System.currentTimeMillis())
        )
    }

    private fun bucketPrefix(now: Long, type: NetworkType): String {
        val day = dayFormat.format(Date(now))
        val suffix = when (type) {
            NetworkType.WIFI -> "wifi"
            NetworkType.CELLULAR -> "mobile"
            NetworkType.OTHER, NetworkType.OFFLINE -> "other"
        }
        return "day_${day}_${suffix}"
    }

    private fun totalKey(type: NetworkType): String = when (type) {
        NetworkType.WIFI -> KEY_TOTAL_WIFI
        NetworkType.CELLULAR -> KEY_TOTAL_MOBILE
        NetworkType.OTHER, NetworkType.OFFLINE -> KEY_TOTAL_OTHER
    }

    private fun uidBytes(): Long {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            return -1L
        }
        return rx.coerceAtLeast(0L) + tx.coerceAtLeast(0L)
    }

    private companion object {
        const val KEY_LAST_UID_BYTES = "last_uid_bytes"
        const val KEY_RESET_AT = "reset_at"
        const val KEY_TOTAL_WIFI = "total_wifi"
        const val KEY_TOTAL_MOBILE = "total_mobile"
        const val KEY_TOTAL_OTHER = "total_other"
        const val DAY_MS = 86_400_000L
    }
}

object StorageUsage {
    fun snapshot(context: Context): StorageSnapshot {
        val appContext = context.applicationContext
        val cache = sequenceOf(appContext.cacheDir, appContext.codeCacheDir)
            .filterNotNull()
            .distinctBy { it.absolutePath }
            .sumOf(::sizeOf)

        val data = sequenceOf(
            appContext.filesDir,
            appContext.getDatabasePath("database.db").parentFile,
            appContext.noBackupFilesDir
        )
            .filterNotNull()
            .distinctBy { it.absolutePath }
            .filterNot { root ->
                root.absolutePath == appContext.cacheDir.absolutePath ||
                    root.absolutePath == appContext.codeCacheDir.absolutePath
            }
            .sumOf(::sizeOf)

        val apk = runCatching { File(appContext.applicationInfo.sourceDir).length() }
            .getOrDefault(0L)

        return StorageSnapshot(
            apkBytes = apk.coerceAtLeast(0L),
            appDataBytes = data.coerceAtLeast(0L),
            cacheBytes = cache.coerceAtLeast(0L)
        )
    }

    fun clearCache(context: Context) {
        val appContext = context.applicationContext
        clearChildren(appContext.cacheDir)
        clearChildren(appContext.codeCacheDir)
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length().coerceAtLeast(0L)
        return file.listFiles()?.sumOf(::sizeOf) ?: 0L
    }

    private fun clearChildren(dir: File?) {
        dir?.listFiles()?.forEach { child ->
            runCatching {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        }
    }
}
