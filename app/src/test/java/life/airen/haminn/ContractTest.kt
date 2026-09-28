package life.airen.haminn

import life.airen.haminn.model.ErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    @Test fun errorCodesAreStableAndUnique() {
        val codes = listOf(
            ErrorCodes.INVALID_ARGUMENT, ErrorCodes.UNSUPPORTED, ErrorCodes.ORIGIN_DENIED,
            ErrorCodes.CAPABILITY_DENIED, ErrorCodes.OS_PERMISSION_DENIED, ErrorCodes.SESSION_EXPIRED,
            ErrorCodes.CANCELLED, ErrorCodes.TIMEOUT, ErrorCodes.CONFLICT, ErrorCodes.QUOTA,
            ErrorCodes.STORAGE, ErrorCodes.NETWORK, ErrorCodes.INTERNAL,
        )
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.matches(Regex("E_[A-Z_]+")) })
    }
}
