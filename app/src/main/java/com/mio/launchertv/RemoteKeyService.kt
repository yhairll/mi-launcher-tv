package com.mio.launchertv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

// Servicio de accesibilidad que "escucha" los botones del control antes que el sistema.
// Asi funcionan Netflix, YouTube, Entradas, Ajustes... aunque el TV los trate como propios,
// y tambien dentro de otras apps.
class RemoteKeyService : AccessibilityService() {

    private var consumedId: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val id = RemoteActions.keyId(event)

        if (event.action == KeyEvent.ACTION_UP) {
            if (consumedId == id) {
                consumedId = null
                return true
            }
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount != 0) return consumedId == id

        val prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        if (RemoteActions.tryLearn(this, prefs, event)) {
            consumedId = id
            return true
        }
        val action = RemoteActions.actionFor(prefs, event) ?: return false
        consumedId = id
        RemoteActions.run(this, action)
        return true
    }
}
