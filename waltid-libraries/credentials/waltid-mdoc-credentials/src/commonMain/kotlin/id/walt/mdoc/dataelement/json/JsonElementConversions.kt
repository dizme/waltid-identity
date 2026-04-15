package id.walt.mdoc.dataelement.json

import id.walt.mdoc.dataelement.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

private val isoFullDateOnly = Regex("^\\d{4}-\\d{2}-\\d{2}$")

fun JsonElement.toDataElement(): AnyDataElement = toDataElement(null)

@OptIn(ExperimentalEncodingApi::class)
fun JsonElement.toDataElement(elementIdentifier: String?): AnyDataElement = when (this) {
    is JsonObject -> mapValues { (key, value) -> value.toDataElement(key) }.toDataElement()
    is JsonArray -> map { element ->
        when (element) {
            is JsonObject -> element.mapValues { (k, v) -> v.toDataElement(k) }.toDataElement()
            else -> element.toDataElement(null)
        }
    }.toDataElement()
    is JsonNull -> NullElement()
    is JsonPrimitive -> {
        when {
            this.isString -> {
                val content = this.content
                if (elementIdentifier == "birth_date" || elementIdentifier == "issue_date" || elementIdentifier == "expiry_date") {
                    if (isoFullDateOnly.matches(content)) {
                        return FullDateElement(LocalDate.parse(content))
                    }
                    return try {
                        TDateElement(Instant.parse(content))
                    } catch (_: Exception) {
                        try {
                            FullDateElement(LocalDate.parse(content))
                        } catch (_: Exception) {
                            StringElement(content)
                        }
                    }
                }
                if (elementIdentifier == "portrait") {
                    try {
                        return ByteStringElement(Base64.decode(content))
                    } catch (_: Exception) {
                        // fall through to plain string
                    }
                }
                StringElement(content)
            }

            this.booleanOrNull != null -> BooleanElement(this.boolean)

            (this.intOrNull ?: this.longOrNull ?: this.floatOrNull ?: this.doubleOrNull) != null -> {
                NumberElement((this.intOrNull ?: this.longOrNull ?: this.floatOrNull ?: this.double))
            }

            else -> NullElement()
        }
    }
}

fun DataElement.toJsonElement(): JsonElement = when (this) {
    is NumberElement -> JsonPrimitive(this.value)
    is StringElement -> JsonPrimitive(this.value)
    is BooleanElement -> JsonPrimitive(this.value)
    is ByteStringElement -> JsonArray(this.value.map { JsonPrimitive(it) })
    is ListElement -> JsonArray(this.value.map { it.toJsonElement() })
    is MapElement -> JsonObject(this.value.mapKeys { it.key.toString() }.mapValues { it.value.toJsonElement() })
    is NullElement -> JsonNull
    is DateTimeElement -> JsonPrimitive(this.value.epochSeconds)
    is FullDateElement -> JsonPrimitive(this.value.toEpochDays() * 24 * 60 * 60)
    is EncodedCBORElement -> this.decode().toJsonElement()
    else -> throw Exception("Unsupported data type")
}

fun DataElement.toUIJson(): JsonElement = when (this) {
    is MapElement -> buildJsonObject {
        this@toUIJson.value.forEach { (key, value) ->
            when (key.type) {
                MapKeyType.int -> put(key.int.toString(), value.toUIJson())
                MapKeyType.string -> put(key.str, value.toUIJson())
            }
        }
    }

    is EncodedCBORElement -> this.decode().toUIJson()
    else -> this.toJsonElement()
}
