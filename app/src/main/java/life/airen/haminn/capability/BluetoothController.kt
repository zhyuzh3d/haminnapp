package life.airen.haminn.capability

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Base64
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothController(context: Context) {
    private class Connection(val emit: (String, JSONObject) -> Unit) { @Volatile var gatt: BluetoothGatt? = null }
    private data class Scan(val callback: ScanCallback)

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val scans = ConcurrentHashMap<String, Scan>()
    private val connections = ConcurrentHashMap<String, Connection>()

    @SuppressLint("MissingPermission")
    fun status(): JSONObject {
        val current = adapter
        val enabled = runCatching { current?.isEnabled }.getOrNull()
        return JSONObject()
            .put("supported", current != null)
            .put("enabled", enabled ?: JSONObject.NULL)
            .put("stateKnown", enabled != null)
            .put("bleSupported", appContext.packageManager.hasSystemFeature("android.hardware.bluetooth_le"))
            .put("multipleAdvertisementSupported", runCatching { current?.isMultipleAdvertisementSupported }.getOrNull() ?: JSONObject.NULL)
            .put("offloadedFilteringSupported", runCatching { current?.isOffloadedFilteringSupported }.getOrNull() ?: JSONObject.NULL)
            .put("name", runCatching { current?.name }.getOrNull() ?: JSONObject.NULL)
            .put("address", JSONObject.NULL)
            .put("systemManaged", true)
    }

    @SuppressLint("MissingPermission")
    fun paired(): JSONObject {
        val current = requireEnabled()
        val items = runCatching { current.bondedDevices }.getOrElse {
            throw HaminnException(ErrorCodes.OS_PERMISSION_DENIED, it.message ?: "无法读取已配对蓝牙设备")
        }.sortedBy { it.address }.map { device ->
            JSONObject().put("address", device.address).put("name", device.name ?: JSONObject.NULL)
                .put("type", device.type).put("bondState", device.bondState)
        }
        return JSONObject().put("devices", JSONArray(items))
    }

    @SuppressLint("MissingPermission")
    fun scan(params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        if (scans.size >= MAX_SCANS) throw HaminnException(ErrorCodes.QUOTA, "已有蓝牙扫描任务")
        val current = requireEnabled()
        if (!appContext.packageManager.hasSystemFeature("android.hardware.bluetooth_le")) {
            throw HaminnException(ErrorCodes.UNSUPPORTED, "当前设备不支持低功耗蓝牙")
        }
        val scanner = current.bluetoothLeScanner ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "系统没有可用的 BLE 扫描器")
        val id = UUID.randomUUID().toString()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (scans.containsKey(id)) emit("bluetooth.scanResult", scanResultJson(id, result))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { if (scans.containsKey(id)) emit("bluetooth.scanResult", scanResultJson(id, it)) }
            }

            override fun onScanFailed(errorCode: Int) {
                emit("bluetooth.scanError", JSONObject().put("subscriptionId", id).put("code", scanError(errorCode)).put("nativeCode", errorCode))
                stopScan(id)
            }
        }
        val mode = when (params.optString("mode", "balanced")) {
            "lowPower" -> ScanSettings.SCAN_MODE_LOW_POWER
            "lowLatency" -> ScanSettings.SCAN_MODE_LOW_LATENCY
            "balanced" -> ScanSettings.SCAN_MODE_BALANCED
            else -> throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "未知蓝牙扫描模式")
        }
        val filters = mutableListOf<ScanFilter>()
        params.optString("serviceUuid").takeIf(String::isNotBlank)?.let {
            filters += ScanFilter.Builder().setServiceUuid(ParcelUuid(parseUuid(it))).build()
        }
        scans[id] = Scan(callback)
        try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(mode).build(), callback)
        } catch (error: Throwable) {
            scans.remove(id)
            throw HaminnException(ErrorCodes.OS_PERMISSION_DENIED, error.message ?: "蓝牙扫描启动失败")
        }
        return JSONObject().put("subscriptionId", id).put("mode", params.optString("mode", "balanced"))
    }

    @SuppressLint("MissingPermission")
    fun stopScan(id: String): JSONObject {
        val scan = scans.remove(id)
        if (scan != null) runCatching { adapter?.bluetoothLeScanner?.stopScan(scan.callback) }
        return JSONObject().put("stopped", scan != null)
    }

    @SuppressLint("MissingPermission")
    fun connect(params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        if (connections.size >= MAX_CONNECTIONS) throw HaminnException(ErrorCodes.QUOTA, "蓝牙连接数量已达上限")
        val current = requireEnabled()
        val address = params.optString("address").uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "蓝牙地址无效")
        val device = runCatching { current.getRemoteDevice(address) }.getOrElse {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "蓝牙地址无效")
        }
        val id = UUID.randomUUID().toString()
        val connection = Connection(emit)
        connections[id] = connection
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (!connections.containsKey(id)) return
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        emit("bluetooth.connected", JSONObject().put("connectionId", id).put("address", address).put("status", status))
                        if (!gatt.discoverServices()) emit("bluetooth.error", errorJson(id, "service-discovery", status))
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        emit("bluetooth.disconnected", JSONObject().put("connectionId", id).put("address", address).put("status", status))
                        disconnect(id)
                    }
                    else -> emit("bluetooth.connectionState", JSONObject().put("connectionId", id).put("state", newState).put("status", status))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                emit(if (status == BluetoothGatt.GATT_SUCCESS) "bluetooth.services" else "bluetooth.error",
                    if (status == BluetoothGatt.GATT_SUCCESS) servicesJson(id, gatt) else errorJson(id, "service-discovery", status))
            }

            @Deprecated("Android 13 compatibility")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                emitCharacteristic(id, "read", characteristic, characteristic.value ?: byteArrayOf(), status, emit)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                emitCharacteristic(id, "read", characteristic, value, status, emit)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                emitCharacteristic(id, "write", characteristic, characteristic.value ?: byteArrayOf(), status, emit)
            }

            @Deprecated("Android 13 compatibility")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                emitCharacteristic(id, "notification", characteristic, characteristic.value ?: byteArrayOf(), BluetoothGatt.GATT_SUCCESS, emit)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                emitCharacteristic(id, "notification", characteristic, value, BluetoothGatt.GATT_SUCCESS, emit)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                emit("bluetooth.subscription", JSONObject().put("connectionId", id)
                    .put("serviceUuid", descriptor.characteristic.service.uuid.toString())
                    .put("characteristicUuid", descriptor.characteristic.uuid.toString())
                    .put("enabled", descriptor.value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true ||
                        descriptor.value?.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == true)
                    .put("status", status).put("success", status == BluetoothGatt.GATT_SUCCESS))
            }
        }
        val gatt = try { device.connectGatt(appContext, params.optBoolean("autoConnect", false), callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE) }
        catch (error: Throwable) {
            connections.remove(id)
            throw HaminnException(ErrorCodes.INTERNAL, error.message ?: "蓝牙连接启动失败", true)
        }
        connection.gatt = gatt
        return JSONObject().put("connectionId", id).put("address", address).put("connecting", true)
    }

    @SuppressLint("MissingPermission")
    fun disconnect(id: String): JSONObject {
        val connection = connections.remove(id)
        connection?.gatt?.let { gatt -> runCatching { gatt.disconnect() }; runCatching { gatt.close() } }
        return JSONObject().put("disconnected", connection != null)
    }

    @SuppressLint("MissingPermission")
    fun services(id: String): JSONObject = servicesJson(id, requireGatt(id))

    @SuppressLint("MissingPermission")
    fun read(params: JSONObject): JSONObject {
        val id = params.getString("connectionId")
        val characteristic = characteristic(requireGatt(id), params)
        if (!requireGatt(id).readCharacteristic(characteristic)) throw HaminnException(ErrorCodes.CONFLICT, "蓝牙读取请求未被接受", true)
        return JSONObject().put("accepted", true).put("connectionId", id)
    }

    @SuppressLint("MissingPermission")
    fun write(params: JSONObject): JSONObject {
        val id = params.getString("connectionId")
        val gatt = requireGatt(id)
        val characteristic = characteristic(gatt, params)
        val value = decodeValue(params.getString("valueBase64"))
        val writeType = if (params.optBoolean("withoutResponse", false)) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        run { characteristic.value = value; characteristic.writeType = writeType }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!accepted) throw HaminnException(ErrorCodes.CONFLICT, "蓝牙写入请求未被接受", true)
        return JSONObject().put("accepted", true).put("connectionId", id)
    }

    @SuppressLint("MissingPermission")
    fun subscribe(params: JSONObject): JSONObject {
        val id = params.getString("connectionId")
        val gatt = requireGatt(id)
        val characteristic = characteristic(gatt, params)
        val enabled = params.optBoolean("enabled", true)
        if (!gatt.setCharacteristicNotification(characteristic, enabled)) throw HaminnException(ErrorCodes.CONFLICT, "蓝牙通知订阅未被接受", true)
        val descriptor = characteristic.getDescriptor(CCCD) ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "该特征没有通知描述符")
        val value = when {
            !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        @Suppress("DEPRECATION")
        run { descriptor.value = value }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!accepted) throw HaminnException(ErrorCodes.CONFLICT, "蓝牙通知配置未被接受", true)
        return JSONObject().put("accepted", true).put("connectionId", id).put("enabled", enabled)
    }

    fun cancelAll() {
        scans.keys.toList().forEach(::stopScan)
        connections.keys.toList().forEach(::disconnect)
    }

    @SuppressLint("MissingPermission")
    private fun requireEnabled(): BluetoothAdapter {
        val current = adapter ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "当前设备没有蓝牙")
        if (!current.isEnabled) throw HaminnException(ErrorCodes.UNSUPPORTED, "蓝牙未开启")
        return current
    }

    private fun requireGatt(id: String): BluetoothGatt = connections[id]?.gatt
        ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "蓝牙连接不存在")

    private fun characteristic(gatt: BluetoothGatt, params: JSONObject): BluetoothGattCharacteristic {
        val serviceUuid = parseUuid(params.getString("serviceUuid"))
        val characteristicUuid = parseUuid(params.getString("characteristicUuid"))
        return gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "蓝牙服务或特征不存在")
    }

    private fun servicesJson(id: String, gatt: BluetoothGatt) = JSONObject().put("connectionId", id).put("services", JSONArray(
        gatt.services.map { service -> JSONObject().put("uuid", service.uuid.toString()).put("type", service.type)
            .put("characteristics", JSONArray(service.characteristics.map { characteristic ->
                JSONObject().put("uuid", characteristic.uuid.toString()).put("properties", characteristic.properties)
                    .put("propertyNames", JSONArray(propertyNames(characteristic.properties)))
                    .put("permissions", characteristic.permissions)
            })) }
    ))

    @SuppressLint("MissingPermission")
    private fun scanResultJson(id: String, result: ScanResult): JSONObject {
        val record = result.scanRecord
        val services = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
        val manufacturer = JSONObject()
        record?.manufacturerSpecificData?.let { data ->
            for (index in 0 until data.size()) manufacturer.put(data.keyAt(index).toString(), Base64.encodeToString(data.valueAt(index), Base64.NO_WRAP))
        }
        return JSONObject().put("subscriptionId", id).put("address", result.device.address)
            .put("name", runCatching { result.device.name }.getOrNull() ?: record?.deviceName ?: JSONObject.NULL)
            .put("rssiDbm", result.rssi).put("timestampNanos", result.timestampNanos)
            .put("connectable", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else JSONObject.NULL)
            .put("serviceUuids", JSONArray(services)).put("manufacturerData", manufacturer)
    }

    private fun emitCharacteristic(id: String, operation: String, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int, emit: (String, JSONObject) -> Unit) {
        emit("bluetooth.characteristic", JSONObject().put("connectionId", id).put("operation", operation)
            .put("serviceUuid", characteristic.service.uuid.toString()).put("characteristicUuid", characteristic.uuid.toString())
            .put("valueBase64", Base64.encodeToString(value, Base64.NO_WRAP)).put("status", status)
            .put("success", status == BluetoothGatt.GATT_SUCCESS))
    }

    private fun decodeValue(raw: String): ByteArray {
        val bytes = try { Base64.decode(raw, Base64.DEFAULT) } catch (_: Throwable) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "valueBase64 不是合法 Base64")
        }
        if (bytes.size > MAX_VALUE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "单次蓝牙数据超过 $MAX_VALUE_BYTES 字节")
        return bytes
    }

    private fun parseUuid(raw: String): UUID = try { UUID.fromString(raw) } catch (_: Throwable) {
        throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "蓝牙 UUID 无效")
    }

    private fun errorJson(id: String, operation: String, status: Int) = JSONObject()
        .put("connectionId", id).put("operation", operation).put("status", status)

    private fun propertyNames(properties: Int): List<String> = listOfNotNull(
        "read".takeIf { properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
        "write".takeIf { properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
        "writeWithoutResponse".takeIf { properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
        "notify".takeIf { properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
        "indicate".takeIf { properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
    )

    private fun scanError(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "already-started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "unsupported"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal"
        else -> "unknown"
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_SCANS = 1
        private const val MAX_CONNECTIONS = 4
        private const val MAX_VALUE_BYTES = 512
    }
}
