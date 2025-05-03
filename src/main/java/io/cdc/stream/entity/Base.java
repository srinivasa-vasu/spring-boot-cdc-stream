package io.cdc.stream.entity;

import io.cdc.stream.event.OPERATION;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Setter
@Getter
@Accessors(fluent = true)
public class Base {

	@Transient
	OPERATION operation;

}
