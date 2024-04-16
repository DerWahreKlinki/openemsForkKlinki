package io.openems.edge.victron.enums;

import io.openems.common.types.OptionsEnum;

public enum VEBusBMSError implements OptionsEnum{
	UNDEFINED(-1, "undefined"),
	OFF(0, "Off"),
	LOW_POWER(1, "Low Power"),
	FAULT(2, "Fault State"),
	BULK(3, "Bulk State"),
	Absorption(4, "Absorption"),
	FLOAT(5, "Float"),
	STORAGE(6, "Storage"),
	EQUALIZE(7, "Equalize"),
	PASSTHRU(8, "Passthru"),
	INVERTING(9, "Inverting"),
	POWER_ASSIST(10, "Power assist"),
	POWER_SUPPLY(11, "Power supply"),
	SUSTAIN(244, "Sustain"),
	EXTERNAL_CONTROL(252, "External Control");
	
	
    private final int value;
    private final String option;

    private VEBusBMSError(int value, String option) {
	this.value = value;
	this.option = option;
    }

    
    public int getValue() {
	return this.value;
    }

    
    public String getName() {
	return this.option;
    }

    
    public OptionsEnum getUndefined() {
	return UNDEFINED;
    }

}
