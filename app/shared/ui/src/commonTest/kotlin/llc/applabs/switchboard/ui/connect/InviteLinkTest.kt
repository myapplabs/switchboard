package llc.applabs.switchboard.ui.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InviteLinkTest {
    @Test fun `invite links carry the hub and the code in the fragment`() {
        assertEquals("http://10.0.0.158:8780" to "XFNA-JYAG-CY2Q",
            ConnectViewModel.parseInviteLink("https://switchboard.applabs.llc/join#hub=http%3A%2F%2F10.0.0.158%3A8780&code=XFNA-JYAG-CY2Q"))
        assertEquals("https://mini.tail.ts.net" to "ABCD-EFGH-JKLM",
            ConnectViewModel.parseInviteLink("https://switchboard.applabs.llc/join#code=ABCD-EFGH-JKLM&hub=https%3A%2F%2Fmini.tail.ts.net"))
        assertNull(ConnectViewModel.parseInviteLink("https://switchboard.applabs.llc/join"))
        assertNull(ConnectViewModel.parseInviteLink("10.0.0.158:8780"))
    }
}
