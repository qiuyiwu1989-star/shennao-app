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

    /**
     * 最近一次失败的原因。
     *
     * Android 的 GATT 失败只给一个 status 数字，不报出来就完全无从查起——
     * 用户看到的是「连不上」，而 133 和 8 是完全不同的两件事。
     */
    var lastError: String? = null
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

    /**
     * 开始之前先自查。**在用户点任何东西之前就该知道答案。**
     *
     * 不查的话，蓝牙关着时扫描会安静地返回空列表，而界面只能猜着说
     * 「确认录音笔开着」——把用户指向错误的方向。
     */
    fun readiness(hasPermission: Boolean): Readiness {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: return Readiness.NO_BLE
        if (!ctx.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) return Readiness.NO_BLE
        if (!adapter.isEnabled) return Readiness.BLUETOOTH_OFF
        if (!hasPermission) return Readiness.NO_PERMISSION
        return Readiness.READY
    }

    // ---- 扫描 ----

    private var scanCb: ScanCallback? = null

    /**
     * 扫描。**不按服务 UUID 过滤。**
     *
     * 2026-09-01 用户实测「扫不到」，根因就在这里：我原来加了
     * `ScanFilter.setServiceUuid(0xAE20)`。而 **BLE 广播包只有 31 字节**，
     * 厂商经常放不下、或者干脆不把 service UUID 放进广播——
     * 它只在连上之后的服务发现里才出现。按它过滤等于把设备自己滤掉了。
     *
     * Mac 端一直是对的：`scanForPeripherals(withServices: nil)`，
     * 扫所有设备，把「有没有广播 0xAE20」当成排序提示而不是过滤条件。
     * 移植时我自作主张改成了过滤，这是典型的「看起来更干净、实际上更错」。
     */
    @SuppressLint("MissingPermission")
    fun scan(onFound: (BleDevice) -> Unit): Boolean {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.bluetoothLeScanner ?: run {
            move(BleState.FAILED); return false
        }
        stopScan()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val ours = ParcelUuid(UUID.fromString(Proto.SERVICE_MAIN))
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.device.name ?: r.scanRecord?.deviceName ?: return  // 无名的是信标噪音
                val adv = r.scanRecord?.serviceUuids?.any { it == ours } == true
                onFound(BleDevice(r.device.address, name, r.rssi, adv))
            }
            override fun onScanFailed(errorCode: Int) {
                lastError = "扫描启动失败（代码 $errorCode）"
                move(BleState.FAILED)
            }
        }
        scanCb = cb
        return runCatching {
            // 不传 filter：扫所有设备。见上面注释。
            scanner.startScan(null, settings, cb); move(BleState.SCANNING); true
        }.getOrElse {
            // 没权限会抛 SecurityException——说清楚是权限而不是"设备有问题"
            lastError = "没有扫描权限"
            move(BleState.FAILED); false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        scanCb?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCb = null
    }

    // ---- 连接 ----

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var attempt = 0
    private var lastId: String? = null

    /**
     * 连接。三个 Android 上出了名的坑，一个都不能少：
     *
     * 1. **停掉扫描之后不能立刻连**。蓝牙控制器还在扫描状态里，
     *    这时发起连接几乎必然拿到 133。要等它把扫描真正停下来。
     * 2. **connectGatt 要在主线程调**。在别的线程调，部分机型（尤其国产 ROM）
     *    会把回调派发到一个已经没了的 looper 上，表现是「连了但永远没有回调」。
     * 3. **133 要自动重试**。它多半是瞬时的——蓝牙栈忙、设备刚好在广播间隙。
     *    让用户自己反复点，只会把 GATT 客户端耗光（每个 App 只有 32 个），
     *    然后变成「越试越连不上」。
     */
    @SuppressLint("MissingPermission")
    fun connect(deviceId: String): Boolean {
        lastId = deviceId
        attempt = 0
        return doConnect(deviceId)
    }

    @SuppressLint("MissingPermission")
    private fun doConnect(deviceId: String): Boolean {
        stopScan()
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false.also { lastError = "蓝牙不可用"; move(BleState.FAILED) }
        if (!adapter.isEnabled) {
            lastError = "手机蓝牙没开"
            move(BleState.FAILED); return false
        }
        val dev = runCatching { adapter.getRemoteDevice(deviceId) }.getOrNull()
            ?: return false.also { lastError = "设备地址无效"; move(BleState.FAILED) }
        move(BleState.CONNECTING)
        queue = GattQueue { op -> runCatching { op.run() }.getOrDefault(false) }
        // 上一次的 gatt 一定要先关掉再开新的，否则句柄泄漏，最后全部 133
        runCatching { gatt?.close() }
        gatt = null
        // 停扫描到发起连接之间留一拍：控制器还在扫描状态时连接几乎必然 133
        main.postDelayed({
            gatt = runCatching {
                dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            }.getOrNull()
            if (gatt == null) { lastError = "发起连接失败"; move(BleState.FAILED) }
        }, SETTLE_MS)
        return true
    }

    /** 133 之类的瞬时失败自动再来一次，最多三次。 */
    private fun retryOrFail(status: Int) {
        val id = lastId
        if (id != null && attempt < MAX_ATTEMPTS && status == 133) {
            attempt++
            lastError = "第 $attempt 次没连上，正在重试…"
            main.postDelayed({ doConnect(id) }, RETRY_MS * attempt)
            return
        }
        lastError = gattStatusHint(status) +
            if (attempt > 0) "（已自动重试 $attempt 次）" else ""
        move(BleState.FAILED)
    }

    /** 把 GATT 的状态码翻成能指导下一步的人话。 */
    private fun gattStatusHint(status: Int): String = when (status) {
        133 -> "连不上（133）。这是安卓上最常见的蓝牙失败码，按可能性排：\n" +
               "1. 录音笔已经被别的设备连着（电脑、另一台手机）——BLE 一次只能连一个\n" +
               "2. 它进入了休眠，按一下它的按键再试\n" +
               "3. 离得太远或有遮挡，靠近到一米内\n" +
               "如果刚才在电脑上用过它，先在电脑上断开连接（不只是关蓝牙）。"
        8 -> "连接超时（8）。设备可能走远了或休眠了，按一下它的按键再试。"
        19 -> "设备主动断开了（19）。"
        22 -> "连接被本机终止（22）。"
        else -> "连不上（状态码 $status）。"
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS &&
                newState != BluetoothProfile.STATE_CONNECTED) {
                // **失败也必须 close()**。不 close 的话 GATT 客户端会泄漏，
                // Android 每个 App 只有 32 个，用完之后所有连接都会以 133 失败——
                // 表现是「第一次连不上，后面越试越连不上」。
                runCatching { g.close() }
                gatt = null
                retryOrFail(status)
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                attempt = 0
                lastError = null
                // MTU 先协商：默认 23 减 3 只剩 20，而下载请求 36 字节必须一次写完
                queue.enqueue("协商MTU", awaitsCallback = true) { g.requestMtu(185) }
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
            queue.enqueue("发现服务", awaitsCallback = true) { g.discoverServices() }
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
                queue.enqueue("开通知 $u", awaitsCallback = true) {
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
            // 合成操作：不会有 GATT 回调，所以 awaitsCallback = false。
            // 写成 true 会把队列永久卡死——之后每次写入都排在后面发不出去。
            queue.enqueue("就绪", awaitsCallback = false) { move(BleState.READY); true }
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
        queue.enqueue("写 ${frame.size}B", awaitsCallback = true) {
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

    private companion object {
        /** 停扫描到发起连接之间的间隔。短于这个几乎必然拿到 133——
         *  蓝牙控制器还在扫描状态里。 */
        const val SETTLE_MS = 400L
        /** 重试间隔按次数递增：第一次 0.6 秒，第二次 1.2 秒。 */
        const val RETRY_MS = 600L
        const val MAX_ATTEMPTS = 3
    }
}
