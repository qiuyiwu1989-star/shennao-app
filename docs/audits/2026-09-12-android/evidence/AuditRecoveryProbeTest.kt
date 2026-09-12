package com.qiuyiwu.shennao.record
import com.qiuyiwu.shennao.Http
import com.qiuyiwu.shennao.HttpResponse
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AuditRecoveryProbeTest {
    private fun probe(failingEndpoint: String) {
        val root = Files.createTempDirectory("shennao-audit-").toFile()
        try {
            val v = FileVault(root)
            val s = v.newSession(SessionMeta("audit-local-only", "test", 1700000000000, finished=true, orgId="org-a"))
            val seg = Segment(0,0,60000,Segment.State.UPLOADED)
            v.segmentFile(s,seg).writeBytes(ByteArray(100))
            val http = object : Http {
                override fun request(method: String,url: String,headers: Map<String,String>,body: String?): HttpResponse = when {
                    url.endsWith("/api/recordings") -> HttpResponse(200, """{"session":{"id":"audit-session","status":"uploading"}}""")
                    url.endsWith(failingEndpoint) -> HttpResponse(503,"{}")
                    else -> HttpResponse(200,"{}")
                }
                override fun requestBytes(method:String,url:String,headers:Map<String,String>,body:ByteArray) = HttpResponse(200,"{}")
            }
            val result = Uploader(http,v,"https://audit.invalid") { "fake-token" to "org-a" }.drain(s)
            assertTrue(result is DrainResult.Failed && result.retryable)
            assertTrue(v.sessions().isNotEmpty())
            // Identical predicate used by Resume.pending; UploadWorker considers zero successful.
            val pending = v.sessions().sumOf { id -> v.segments(id).count { it.state == Segment.State.SEALED } }
            assertEquals("Observed defect: unfinished session is invisible to retry predicate",0,pending)
            println("CONFIRMED $failingEndpoint 503 -> retryable failure + retained session + pending=0 (worker success predicate)")
        } finally { root.deleteRecursively() }
    }
    @Test fun stopFailureDisappearsFromPendingCount() = probe("/stop")
    @Test fun finalizeFailureDisappearsFromPendingCount() = probe("/finalize")
    @Test fun transportFailureAfterTicketDeletesLocalAudio() {
        val root=Files.createTempDirectory("audit-network-").toFile()
        try {
            val v=FileVault(root)
            val s=v.newSession(SessionMeta("audit-network", "test",1700000000000,finished=true,orgId="org-a"))
            val seg=Segment(0,0,60000,Segment.State.SEALED)
            v.segmentFile(s,seg).writeBytes(ByteArray(100))
            val http=object:Http {
                override fun request(method:String,url:String,headers:Map<String,String>,body:String?) = when {
                    url.endsWith("/api/recordings") -> HttpResponse(200,"""{"session":{"id":"audit","status":"recording"}}""")
                    url.endsWith("/ticket") -> HttpResponse(200,"""{"uploadUrl":"https://audit.invalid/put"}""")
                    else -> HttpResponse(0,"network unavailable")
                }
                override fun requestBytes(method:String,url:String,headers:Map<String,String>,body:ByteArray) = HttpResponse(0,"network unavailable")
            }
            val result=Uploader(http,v,"https://audit.invalid") { "fake" to "org-a" }.drain(s)
            assertTrue("Observed false success: $result",result is DrainResult.Done)
            assertTrue("Observed data deletion despite transport failures",v.sessions().isEmpty())
            println("CONFIRMED disconnect after ticket -> PUT/complete/stop/finalize status=0 -> Done -> LOCAL AUDIO DELETED")
        } finally {root.deleteRecursively()}
    }
}
