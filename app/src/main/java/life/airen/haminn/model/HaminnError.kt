package life.airen.haminn.model

class HaminnException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
) : Exception(message)

object ErrorCodes {
    const val INVALID_ARGUMENT = "E_INVALID_ARGUMENT"
    const val UNSUPPORTED = "E_UNSUPPORTED"
    const val ORIGIN_DENIED = "E_ORIGIN_DENIED"
    const val CAPABILITY_DENIED = "E_CAPABILITY_DENIED"
    const val OS_PERMISSION_DENIED = "E_OS_PERMISSION_DENIED"
    const val SESSION_EXPIRED = "E_SESSION_EXPIRED"
    const val CANCELLED = "E_CANCELLED"
    const val TIMEOUT = "E_TIMEOUT"
    const val CONFLICT = "E_CONFLICT"
    const val QUOTA = "E_QUOTA"
    const val STORAGE = "E_STORAGE"
    const val NETWORK = "E_NETWORK"
    const val INTERNAL = "E_INTERNAL"
    const val DEV_MODE_REQUIRED = "E_DEV_MODE_REQUIRED"
    const val PROTECTED_TARGET = "E_PROTECTED_TARGET"
}
