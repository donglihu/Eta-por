package io.github.mangi.eta.core

import android.content.Context
import java.io.File

/**
 * 公共存储目录。
 *
 * ETA 的对外产物统一落在 `/storage/emulated/0/ETA` 下；所有写入点在真正落盘前先调用
 * [ensureDirectory]，避免根目录或子目录不存在时直接失败。
 */
internal object SharedStorage {

    /** 公共根目录。不要在业务代码里重复硬编码这个路径。 */
    const val BASE_DIR = "/storage/emulated/0/ETA"

    /** 诊断包与巡检产物。 */
    const val DIAGNOSTICS_DIR = "$BASE_DIR/诊断包备份"

    /** 自动导出的诊断包；只在这里滚动保留。 */
    const val DIAGNOSTICS_AUTO_DIR = "$DIAGNOSTICS_DIR/自动"

    /** 巡检报告、结论与附带诊断包。 */
    const val DIAGNOSTICS_PATROL_DIR = "$DIAGNOSTICS_DIR/巡检"

    /** 用户可编辑的自定义巡检规则。 */
    const val DIAGNOSTICS_RULES_FILE = "$DIAGNOSTICS_DIR/自定义检测.json"

    /** 自动备份目录；巡检只读统计体积。 */
    const val BACKUP_RECORD_DIR = "$BASE_DIR/数据备份记录"

    /** 灵屏日志导出目录。 */
    const val LINGSCREEN_LOG_DIR = "$BASE_DIR/灵屏日志"

    /**
     * 确保目录存在。
     *
     * `mkdirs()` 会连带创建缺失的父目录，包括 `/storage/emulated/0/ETA` 这一级；
     * 已经存在时直接返回 true，不重复创建。
     */
    fun ensureDirectory(dir: File): Boolean = dir.isDirectory || dir.mkdirs() || dir.isDirectory

    /**
     * 解析一个真正可写的公共子目录；首选不可写时回落到应用专属外部目录。
     *
     * 为什么需要：`BASE_DIR` 是公共存储路径，Android 11+ 分区存储下写入需要
     * MANAGE_EXTERNAL_STORAGE（「所有文件访问权限」）。用户一旦撤销该权限，
     * 巡检报告、自定义规则、灵屏日志导出会全部失败，而失败信息只有笼统的「无法创建目录」。
     *
     * 回落目录不需要任何存储权限，保证功能不因权限变化而整体失效。
     *
     * @param subDir 相对 `BASE_DIR` 的子目录名；回落时挂在应用专属目录的同名子目录下
     * @return 可写目录；首选与回落都不可写时返回 null，由调用方给出明确错误
     */
    fun writableDir(context: Context, subDir: String): File? {
        val preferred = File(BASE_DIR, subDir)
        if (isWritable(preferred)) return preferred
        val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, subDir)
        if (isWritable(fallback)) {
            EtaTrace.log(
                "shared_storage.fallback",
                "公共目录不可写，回落应用专属目录 dir=$subDir path=${fallback.absolutePath}",
            )
            return fallback
        }
        EtaTrace.log(
            "shared_storage.unavailable",
            "公共目录与回落目录都不可写 dir=$subDir preferred=${preferred.absolutePath}",
        )
        return null
    }

    /**
     * 建目录 + 写探针。
     *
     * 分区存储下 FUSE 会拖到真正写那一刻才拒绝，只看 `canWrite` 不够，
     * 必须真的写一个空文件再删掉。
     */
    fun isWritable(dir: File): Boolean = try {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            false
        } else if (!dir.isDirectory) {
            false
        } else {
            val probe = File(dir, ".eta-write-probe")
            probe.writeBytes(ByteArray(0))
            probe.delete()
            true
        }
    } catch (_: java.io.IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
