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

    @Test fun `previews drop Markdown marks, table rules and Hermes's job wrapper`() {
        val result = "Cronjob Response: Pulse\n(job_id: abc123)\n-------------\n## **Brief**\n| Item | Status |\n|------|--------|\n| Roads | `funded` |\n---\nDone"
        assertEquals("Brief\nItem · Status\nRoads · funded\nDone", plainText(result))
        assertEquals("Hello", unwrapJobResult("Hello"))
    }
}
