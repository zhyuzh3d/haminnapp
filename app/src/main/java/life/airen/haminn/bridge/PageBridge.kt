package life.airen.haminn.bridge

interface PageBridge {
    fun install()
    fun navigationStarted()
    fun navigationCommitted()
    fun recoverAfterUncommittedNavigation(): Boolean
    fun close()
    fun emit(name: String, data: Any?)
}
