package com.bwssystems.HABridge;

import com.bwssystems.HABridge.devicemanagmeent.ResourceHandler;
import com.bwssystems.HABridge.hue.HueMulatorHandler;

public interface Home extends HueMulatorHandler, ResourceHandler {
	Home createHome(BridgeSettings bridgeSettings);
	void closeHome();
}
