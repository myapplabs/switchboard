package llc.applabs.switchboard.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Files go up as raw bytes with their type and name, and come down as bytes. */
class UploadTest {
    @Test fun `an upload carries the file's bytes, type and name`() = runTest {
        val bytes = ByteArray(3000) { (it % 251).toByte() }
        var sent: ByteArray? = null
        var type: String? = null
        var name: String? = null
        val tokens = TokenStore(MemoryStore()).apply { hubUrl = "http://hub.test"; accessToken = "t" }
        val client = HubClient(MockEngine { req ->
            if (req.method == HttpMethod.Post) {
                sent = req.body.toByteArray(); type = req.body.contentType?.toString(); name = req.headers["X-Filename"]
                respond("""{"id":"att_1","name":"a b.jpg","content_type":"image/jpeg","size":3000}""")
            } else respond(bytes)
        }, tokens)
        client.upload("/sessions/s/attachments", "a b.jpg", "image/jpeg", bytes)
        assertContentEquals(bytes, sent)
        assertEquals("image/jpeg", type)
        assertEquals("a%20b.jpg", name)
        assertContentEquals(bytes, client.download("/attachments/att_1"))
    }
}
