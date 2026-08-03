package io.cdc.stream.event;

public enum OPERATION {

	/** create */
	c,
	/** update */
	u,
	/** delete */
	d,
	/** snapshot read */
	r,
	/** truncate */
	t,
	/** logical decoding message — carries no row */
	m

}
