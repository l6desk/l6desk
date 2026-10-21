package io.github.l6desk.bridge

import java.io.File

/**
 * Powers the Pi off when the app asks. The bridge runs as an unprivileged, locked-down service, so
 * it doesn't shut down itself: it creates [request], and the root-run `l6desk-poweroff.path` unit
 * (installed by pi-setup.sh) sees the file and powers off. The folder is the service's own runtime
 * directory (`RuntimeDirectory=` in l6desk-bridge.service), so it only exists when the bridge runs
 * under systemd: run by hand (e.g. on a desktop for testing), the bridge never offers to power off.
 */
class PowerOff(private val request: File = File(DEFAULT_REQUEST)) {
    /** Whether a request can be made here. */
    val available: Boolean get() = request.parentFile?.let { it.isDirectory && it.canWrite() } == true

    /** Asks for the power-off; false if the request couldn't be written. */
    fun request(): Boolean = runCatching { request.writeText("Requested by the app\n") }.isSuccess

    companion object {
        const val DEFAULT_REQUEST = "/run/l6desk-bridge/power-off"
    }
}
