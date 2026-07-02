package com.tvl.incidentaliq.core

object LogBus {
    var listener: ((String) -> Unit)? = null
    fun post(msg: String) { listener?.invoke(msg) }
}
