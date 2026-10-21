package llc.applabs.switchboard.hub

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.nio.file.Files
import java.nio.file.Path

/** Events the hub emits must validate against the contract (contract/events.schema.json, copied from the api repo). */
object ContractSchema {
    private val mapper = ObjectMapper()
    private val events = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(Files.readString(Path.of("contract/events.schema.json")))

    /** Validation messages for one event payload (empty = valid). */
    fun eventErrors(json: String): List<String> = events.validate(mapper.readTree(json)).map { it.message }
}
