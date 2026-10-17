package com.railspotting.android.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.railspotting.android.core.Native

/** RTL-SDR dongles: the RTL2832U USB IDs librtlsdr knows (native/rtl-sdr, src/librtlsdr.c). */
object RtlIds {
    val known: Set<Pair<Int, Int>> = setOf(
        0x0BDA to 0x2832, 0x0BDA to 0x2838,
        0x0413 to 0x6680, 0x0413 to 0x6F0F,
        0x0458 to 0x707F,
        0x0CCD to 0x00A9, 0x0CCD to 0x00B3, 0x0CCD to 0x00B4, 0x0CCD to 0x00B5, 0x0CCD to 0x00B7,
        0x0CCD to 0x00B8, 0x0CCD to 0x00B9, 0x0CCD to 0x00C0, 0x0CCD to 0x00C6, 0x0CCD to 0x00D3,
        0x0CCD to 0x00D7, 0x0CCD to 0x00E0,
        0x1554 to 0x5020,
        0x15F4 to 0x0131, 0x15F4 to 0x0133,
        0x185B to 0x0620, 0x185B to 0x0650, 0x185B to 0x0680,
        0x1B80 to 0xD393, 0x1B80 to 0xD394, 0x1B80 to 0xD395, 0x1B80 to 0xD397, 0x1B80 to 0xD398,
        0x1B80 to 0xD39D, 0x1B80 to 0xD3A4, 0x1B80 to 0xD3A8, 0x1B80 to 0xD3AF, 0x1B80 to 0xD3B0,
        0x1D19 to 0x1101, 0x1D19 to 0x1102, 0x1D19 to 0x1103, 0x1D19 to 0x1104,
        0x1F4D to 0xA803, 0x1F4D to 0xB803, 0x1F4D to 0xC803, 0x1F4D to 0xD286, 0x1F4D to 0xD803,
    )

    fun isDongle(vendorId: Int, productId: Int) = (vendorId to productId) in known
}

/**
 * The RTL-SDR dongle over Android's USB host API, no root needed. Asks for permission when the
 * dongle is found, opens it, and gives its file descriptor to the core, whose librtlsdr drives
 * it. The connection stays open here while the core uses it.
 */
class Dongle(private val context: Context, private val onChange: () -> Unit) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private var conn: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    /** Permission was asked for this one, so the prompt is not repeated while it stays plugged in. */
    private var asked: String? = null
    @Volatile var status: String = "No dongle connected"
        private set

    val connected: Boolean get() = conn != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                unlocked()
                return
            }
            val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (isDongle(dev)) {
                    asked = null
                    if (dev.deviceName == device?.deviceName) close("Dongle unplugged")
                }
                ACTION_PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    open(dev)
                } else {
                    status = "USB access to the dongle was not allowed"
                    onChange()
                }
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        poll()
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // not registered
        }
        close("No dongle connected")
    }

    /**
     * Look for the dongle. Android sends "attached" to the activity, not to receivers, so the
     * service calls this on its tick as well.
     */
    @Synchronized
    fun poll() {
        if (conn != null) return
        val dev = usb.deviceList.values.firstOrNull { isDongle(it) } ?: return
        if (usb.hasPermission(dev)) {
            open(dev)
            return
        }
        if (asked == dev.deviceName) return
        asked = dev.deviceName
        status = WAITING
        onChange()
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        // the system adds the device and the answer to this intent, so it must be mutable
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        usb.requestPermission(dev, pi)
    }

    /**
     * The phone was unlocked. Android's USB protection (Advanced Protection) can reset the USB
     * port on unlock, and the dongle comes back as a new device without USB access. A request
     * made while the phone was locked shows no prompt, so ask again now that it can be seen.
     */
    @Synchronized
    private fun unlocked() {
        if (conn != null) return
        asked = null
        poll()
    }

    /**
     * Open the dongle's connection afresh: the core has had no data from it. After a USB I/O
     * error or a port reset the old connection is dead, though the dongle may still be listed.
     * If it is gone, or needs USB access again, [poll] says so.
     */
    @Synchronized
    fun reconnect(why: String) {
        Log.w(TAG, "reopening the dongle: $why")
        close("Reconnecting to the dongle")
        poll()
    }

    @Synchronized
    private fun open(dev: UsbDevice) {
        if (conn != null) return
        val c = usb.openDevice(dev)
        if (c == null) {
            status = "Could not open the dongle"
            Log.w(TAG, status)
            onChange()
            return
        }
        conn = c
        device = dev
        status = "Dongle connected"
        Log.i(TAG, "dongle open: %04x:%04x".format(dev.vendorId, dev.productId))
        Native.dongleAttached(c.fileDescriptor)
        onChange()
    }

    @Synchronized
    fun close(why: String) {
        val c = conn ?: run {
            status = why
            return
        }
        // the core lets go of the descriptor before it is closed
        Native.dongleDetached()
        c.close()
        conn = null
        device = null
        status = why
        Log.i(TAG, "dongle closed: $why")
        onChange()
    }

    private fun isDongle(d: UsbDevice) = RtlIds.isDongle(d.vendorId, d.productId)

    companion object {
        private const val TAG = "RailSpotting"
        private const val ACTION_PERMISSION = "com.railspotting.android.USB_PERMISSION"
        /** Shown in the listening notification while Android's USB prompt is pending. */
        const val WAITING = "Unlock the phone and allow USB access to the dongle"
    }
}
