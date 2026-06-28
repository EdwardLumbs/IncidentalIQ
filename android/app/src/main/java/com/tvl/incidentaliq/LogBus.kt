package com.tvl.incidentaliq

object LogBus {
    var listener: ((String) -> Unit)? = null
    fun post(msg: String) { listener?.invoke(msg) }
}
