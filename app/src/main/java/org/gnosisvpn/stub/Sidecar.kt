package org.gnosisvpn.stub

import java.io.File

/**
 * Developer screen: where the core pays through the relayer side-car (a separate, unpaid session
 * to the exit for the Curvy relayer). Most exits do not allow that session target, and on a phone
 * the relayer is reached directly anyway, so by default only exits pinned with `sidecar = "on"`
 * (the lab exits, LabExits) use it. "All exits" sets GNOSISVPN_ANDROID_SIDECAR=all for the core
 * (rust/patches: android_sidecar), which reads it at every connect. An exit without the target
 * then costs about 150 s before the core connects without the side-car.
 */
object Sidecar {
    private const val FLAG = "sidecar-all-exits"
    const val ENV = "GNOSISVPN_ANDROID_SIDECAR"

    fun allExits(noBackup: File) = File(noBackup, FLAG).exists()

    fun set(noBackup: File, all: Boolean) {
        val f = File(noBackup, FLAG)
        if (all) f.writeText("") else f.delete()
        apply(noBackup)
    }

    /** Passes the setting to the core (same process: it reads the environment at each connect). */
    fun apply(noBackup: File) {
        runCatching { android.system.Os.setenv(ENV, if (allExits(noBackup)) "all" else "lab", true) }
            .onFailure { LogBuffer.log("side-car setting not applied: ${it.message}") }
    }

    fun label(noBackup: File) = if (allExits(noBackup)) "all exits" else "lab exits"
}
