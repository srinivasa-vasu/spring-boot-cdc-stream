package io.cdc.stream.mapper;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class KeyValueDeserializer extends JsonDeserializer<Object> implements ContextualDeserializer {

	private static final String VALUE_FIELD = "value";
	private JavaType targetType;

	public KeyValueDeserializer() {
	}

	public KeyValueDeserializer(JavaType targetType) {
		this.targetType = targetType;
	}

	@Override
	public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
		ObjectMapper mapper = (ObjectMapper) p.getCodec();
		JsonNode node = mapper.readTree(p);
		JsonNode valueNode = node.get(VALUE_FIELD);

		if (valueNode == null || valueNode.isNull()) return null;

		if (targetType.hasRawClass(Instant.class)) {
			if (valueNode.isNumber()) {
				long micros = valueNode.asLong();
				return Instant.ofEpochMilli(micros / 1000);
			}
			else if (valueNode.isTextual()) {
				return Instant.parse(valueNode.asText()); // Supports ISO-8601 format
			}
		}
		else if (targetType.hasRawClass(LocalDateTime.class)) {
			if (valueNode.isTextual()) {
				// Parse LocalDateTime from ISO-8601 or other formats
				return LocalDateTime.parse(valueNode.asText(), DateTimeFormatter.ISO_DATE_TIME);
			}
		}
		else if (targetType.hasRawClass(ZonedDateTime.class)) {
			if (valueNode.isTextual()) {
				return ZonedDateTime.parse(valueNode.asText());
			}
		}
		else if (targetType.hasRawClass(OffsetDateTime.class)) {
			if (valueNode.isTextual()) {
				return OffsetDateTime.parse(valueNode.asText());
			}
		}

		JsonParser valueParser = valueNode.traverse(mapper);
		valueParser.nextToken();
		return ctxt.readValue(valueParser, targetType);
	}

	@Override
	public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
		JavaType type = property.getType();
		return new KeyValueDeserializer(type);
	}
}

