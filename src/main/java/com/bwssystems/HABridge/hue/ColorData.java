package com.bwssystems.HABridge.hue;

public final class ColorData {
	public enum ColorMode { XY, CT, HS}

	private ColorMode mode;
	private Object data;
	
	public ColorData(ColorMode mode, Object value) {
		this.mode = mode;
		this.data = value;
	}
	
	public Object getData() {
		return data;
	}

	public ColorMode getColorMode() {
		return mode;
	}

	public String toString() {
		String formatString;

		formatString = "Color Data mode: " + mode + ", data: " + data;
		return formatString;
	}
}