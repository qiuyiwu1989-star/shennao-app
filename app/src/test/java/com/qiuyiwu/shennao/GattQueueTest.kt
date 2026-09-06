package com.qiuyiwu.shennao

import com.qiuyiwu.shennao.ble.GattQueue
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** 012 P0-5：三个线程同时往队列里塞、同时报完成，一个操作都不能丢。 */
class GattQueueTest {
    @Test fun `两个线程互打，操作一个不丢、一次只在飞一个`() {
        val ran = AtomicInteger(0); val maxInFlight = AtomicInteger(0); val inFlight = AtomicInteger(0)
        val q = GattQueue { op -> op.run() }
        val done = CountDownLatch(2)
        val threads = (0 until 2).map { t ->
            Thread {
                repeat(500) {
                    q.enqueue("op$t", awaitsCallback = true) {
                        val now = inFlight.incrementAndGet(); maxInFlight.updateAndGet { m -> maxOf(m, now) }
                        ran.incrementAndGet(); true
                    }
                    inFlight.decrementAndGet(); q.onComplete()
                }
                done.countDown()
            }.also { it.start() }
        }
        done.await()
        // 每个 enqueue 都紧跟一个 onComplete，所以所有 1000 个都该跑过
        assertEquals(1000, ran.get())
        assertEquals("同一时刻只该有一个在飞", 1, maxInFlight.get())
        assertEquals(0, q.waiting)
    }
}
