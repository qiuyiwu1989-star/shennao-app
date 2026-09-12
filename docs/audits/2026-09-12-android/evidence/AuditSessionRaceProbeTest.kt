package com.qiuyiwu.shennao
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class AuditSessionRaceProbeTest {
 @Test fun refreshInFlightRestoresSignedOutCredentials() {
  val entered=CountDownLatch(1); val release=CountDownLatch(1)
  val store=object:CredentialStore {
   @Volatile var c:Credentials?=Credentials("old-refresh","org-a","audit@example.invalid")
   override fun load()=c
   override fun save(c:Credentials){this.c=c}
   override fun clear(){c=null}
  }
  val http=object:Http {
   override fun request(method:String,url:String,headers:Map<String,String>,body:String?):HttpResponse {
    entered.countDown(); check(release.await(3,TimeUnit.SECONDS))
    return HttpResponse(200,"""{"access_token":"new-access","refresh_token":"new-refresh"}""")
   }
   override fun requestBytes(method:String,url:String,headers:Map<String,String>,body:ByteArray)=HttpResponse(404,"")
  }
  val c=DeepBrainClient(http,store,"https://audit.invalid","https://audit.invalid","fake")
  val t=Thread {c.validAccessToken()};t.start()
  try {
   assertTrue(entered.await(3,TimeUnit.SECONDS));c.signOut();assertNull(store.load())
  } finally {release.countDown();t.join(3000)}
  assertEquals("Observed defect: old account restored after logout","new-refresh",store.load()?.refreshToken)
  println("CONFIRMED refresh in flight + signOut -> refresh response restores old account credentials")
 }
}
