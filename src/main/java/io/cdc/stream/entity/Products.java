package io.cdc.stream.entity;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.cdc.stream.mapper.KeyValueDeserializer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Entity
@Data
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class Products extends Base {
	@Id
	@JsonDeserialize(using = KeyValueDeserializer.class)
	Long id;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String category;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String ean;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	BigDecimal price;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	@JsonSetter(nulls = Nulls.SKIP)
	int quantity;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	BigDecimal rating;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String title;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String vendor;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	Instant createdAt;
}
