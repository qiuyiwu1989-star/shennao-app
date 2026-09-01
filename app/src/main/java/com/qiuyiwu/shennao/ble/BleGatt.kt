package com.qiuyiwu.shennao.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/*
 * Android 侧的 BLE 通道。这一层**只有真机能验**，所以能挪走的判断都挪走了：
 * 操作串行化在 GattQueue、协议在 Proto/FrameParser、导入流程在 Importer，
 * 三块都在 JVM 上跑得起来。这里剩下的是没法脱离设备的那部分。
 *
 * 几个 Android 特有的坑：
 *   · GATT 一次只受理一个操作 → 全部走 GattQueue
 *   · 开通知不是 setCharacteristicNotification 就完了，**还要写 CCCD 描述符**，
 *     少了这一步设备一个字节都不会推
 *   · 回调在 binder 线程上来，别在里面做重活
 *   · MTU 默认 23，减去 3 字节头只剩 20 —— 而下载请求是 36 字节且**必须一次写完**，
 *     所以连上先协商 MTU
 */

private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class BleGatt(private val ctx: Context) : BleTransport {

    override var state: BleState = BleState.IDLE
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val listeners = mutableMapOf<String, (ByteArray) -> Unit>()
    private var onStateChange: ((BleState) -> Unit)? = null
    private lateinit var queue: GattQueue

    fun observeState(cb: (BleState) -> Unit) { onStateChange = cb }

    private fun move(s: BleState) {
        state = s
        onStateChange?.invoke(s)
    }

    // ---- 扫描 ----

    private var scanCb: ScanCallback? = null

    /**
     * 按服务 UUID 过滤扫描。
     *
     * 不做「扫到所有设备再自己筛」：那样会把周围几十个蓝牙设备都列出来，
     * 用户得在里面找自己的录音笔——而他多半不知道它叫什么。
     */
    @SuppressLint("MissingPermission")
    fun scan(onFound: (BleDevice) -> Unit): Boolean {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.bluetoothLeScanner ?: run {
            move(BleState.FAILED); return false
        }
        stopScan()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(Proto.SERVICE_MAIN))).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.device.name ?: r.scanRecord?.deviceName ?: "未命名设备"
                onFound(BleDevice(r.device.address, name, r.rssi))
            }
            override fun onScanFailed(errorCode: Int) { move(BleState.FAILED) }
        }
        scanCb = cb
        return runCatching {
            scanner.startScan(listOf(filter), settings, cb); move(BleState.SCANNING); true
        }.getOrElse { move(BleState.FAILED); false }   // 没权限会抛 SecurityException
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        scanCb?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCb = null
    }

    // ---- 连接 ----

    @SuppressLint("MissingPermission")
    fun connect(deviceId: String): Boolean {
        stopScan()
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false.also { move(BleState.FAILED) }
        val dev = runCatching { adapter.getRemoteDevice(deviceId) }.getOrNull()
            ?: return false.also { move(BleState.FAILED) }
        move(BleState.CONNECTING)
        queue = GattQueue { op -> runCatching { op.run() }.getOrDefault(false) }
        gatt = runCatching {
            dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) { move(BleState.FAILED); return false }
        return true
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // MTU 先协商：默认 23 减 3 只剩 20，而下载请求 36 字节必须一次写完
                queue.enqueue("协商MTU") { g.requestMtu(185) }
            } else {
                // **断开就要如实说**。留在 READY 上的话，上层会一直往一个死连接里写，
                // 而每一次写都「成功」返回，什么都不会发生。
                move(BleState.DISCONNECTED)
                if (::queue.isInitialized) queue.reset()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            queue.onComplete()
            queue.enqueue("发现服务") { g.discoverServices() }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            queue.onComplete()
            val svc = g.getService(UUID.fromString(Proto.SERVICE_MAIN)) ?: run {
                move(BleState.FAILED); return
            }
            writeChar = svc.getCharacteristic(UUID.fromString(Proto.CHAR_WRITE))
            // 两路通知各写一次 CCCD。**必须串行**，挤在一起发只有第一个生效。
            for (u in listOf(Proto.CHAR_NOTIFY, Proto.CHAR_KEY)) {
                val ch = svc.getCharacteristic(UUID.fromString(u)) ?: continue
                queue.enqueue("开通知 $u") {
                    g.setCharacteristicNotification(ch, true)
                    val d = ch.getDescriptor(CCCD) ?: return@enqueue false
                    // 光调 setCharacteristicNotification 是不够的——不写 CCCD，
                    // 设备一个字节都不会推。这是最常见的「连上了但没数据」。
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(d)
                        }
                    }
                }
            }
            queue.enqueue("就绪") { move(BleState.READY); true }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            queue.onComplete()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            queue.onComplete()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            listeners[c.uuid.toString().lowercase()]?.invoke(c.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            listeners[c.uuid.toString().lowercase()]?.invoke(value)
        }
    }

    // ---- BleTransport ----

    @SuppressLint("MissingPermission")
    override fun write(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        if (state != BleState.READY) return false
        var ok = false
        queue.enqueue("写 ${frame.size}B") {
            ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.value = frame
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    g.writeCharacteristic(ch)
                }
            }
            ok
        }
        return true
    }

    override fun onNotify(char: String, cb: (ByteArray) -> Unit) {
        listeners[char.lowercase()] = cb
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        stopScan()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; writeChar = null
        if (::queue.isInitialized) queue.reset()
        move(BleState.IDLE)
    }
}
