package com.gigiaj.paradise

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.getcapacitor.CapacitorWebView

class ParadiseWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CapacitorWebView(context, attrs) {

    private var lastSystemVisibility: Int = View.VISIBLE

    override fun onWindowVisibilityChanged(visibility: Int) {
        lastSystemVisibility = visibility
        if (ParadiseForegroundService.isCallActive && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    fun syncCallVisibilityState() {
        if (ParadiseForegroundService.isCallActive) {
            super.onWindowVisibilityChanged(View.VISIBLE)
            onResume()
            resumeTimers()
        } else if (lastSystemVisibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(lastSystemVisibility)
        }
    }
}
