package llc.applabs.switchboard.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test fun `inline markers are removed and styled`() {
        val s = inline("Up for **13 days**, run `uptime` — *roughly*")
        assertEquals("Up for 13 days, run uptime — roughly", s.text)
        assertEquals(3, s.spanStyles.size)
    }

    @Test fun `snake_case and arithmetic stay as written`() {
        assertEquals("api_server_key and 2*3*4", inline("api_server_key and 2*3*4").text)
    }
}
