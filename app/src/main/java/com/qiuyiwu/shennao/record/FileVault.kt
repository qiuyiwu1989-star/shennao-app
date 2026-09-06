package com.qiuyiwu.shennao.record

import java.io.File

/**
 * 真实存储。每场录音一个目录，目录名就是它的本地 id。
 *
 * 放在 filesDir 而不是 cacheDir：cacheDir 在存储紧张时会被系统直接清掉，
 * 而这里躺着的是用户唯一一份还没上传的录音。
 */
class FileVault(private val root: File) : Vault {

    private fun dir(session: String) = File(root, session)

    fun newSession(meta: SessionMeta): String {
        // 目录名带上时刻，肉眼排查时能直接看出是哪一场
        val name = "rec-${meta.startedAtEpochMs}-${meta.clientRequestId.take(8)}"
        dir(name).mkdirs()
        writeMeta(name, meta)
        return name
    }

    fun segmentFile(session: String, seg: Segment) = File(dir(session), seg.fileName())

    override fun sessions(): List<String> =
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.startsWith("rec-") }
            .map { it.name }.sorted()

    override fun readMeta(session: String): SessionMeta? =
        File(dir(session), "meta.json").takeIf { it.isFile }
            ?.let { runCatching { SessionMeta.fromJson(it.readText()) }.getOrNull() }

    @Synchronized
    override fun updateMeta(session: String, f: (SessionMeta) -> SessionMeta): SessionMeta? {
        val cur = readMeta(session) ?: return null
        val next = f(cur)
        if (next != cur) writeMeta(session, next)
        return next
    }

    @Synchronized
    override fun writeMeta(session: String, meta: SessionMeta) {
        val d = dir(session).apply { mkdirs() }
        // 先写临时文件再改名。直接覆写的话，掉电会留下半截 json，
        // 而 meta 读不出来 = 这场录音的幂等键丢了 = 它再也推不上去。
        val tmp = File(d, "meta.json.tmp")
        tmp.writeText(meta.toJson())
        tmp.renameTo(File(d, "meta.json"))
    }

    override fun segments(session: String): List<Segment> =
        (dir(session).listFiles() ?: emptyArray())
            .mapNotNull { Segment.parse(it.name) }.sortedBy { it.sequence }

    override fun segmentPath(session: String, seg: Segment): File? = segmentFile(session, seg).takeIf { it.isFile }
    override fun readSegment(session: String, seg: Segment): ByteArray? =
        segmentFile(session, seg).takeIf { it.isFile }?.readBytes()

    override fun rename(session: String, from: Segment, to: Segment): Boolean =
        segmentFile(session, from).renameTo(segmentFile(session, to))

    override fun deleteSession(session: String) { dir(session).deleteRecursively() }
}
