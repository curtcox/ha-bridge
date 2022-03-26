package com.bwssystems.HABridge.devicemanagmeent;

public interface ResourceHandler {
	Object getItems(String type);
	void refresh();
}
