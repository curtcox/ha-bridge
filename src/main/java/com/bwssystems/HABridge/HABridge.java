package com.bwssystems.HABridge;

import static spark.Spark.*;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bwssystems.HABridge.devicemanagmeent.*;
import com.bwssystems.HABridge.hue.HueMulator;
import com.bwssystems.HABridge.plugins.http.HttpClientPool;
import com.bwssystems.HABridge.upnp.UpnpListener;
import com.bwssystems.HABridge.upnp.UpnpSettingsResource;
import com.bwssystems.HABridge.util.UDPDatagramSender;

public final class HABridge {

	private DeviceResource theResources;
	private HueMulator theHueMulator;
	private UpnpSettingsResource theSettingResponder;

	private final BridgeSettings bridgeSettings = new BridgeSettings();
	private final Version theVersion = new Version();
	private final HomeManager homeManager = new HomeManager();
	private static final Logger log = LoggerFactory.getLogger(HABridge.class);
	private static SystemControl theSystem;
	
	/*
	 * This program is based on the work of armzilla from this github repository:
	 * https://github.com/armzilla/amazon-echo-ha-bridge
	 * 
	 * This is the main entry point to start the amazon echo bridge.
	 * 
	 * This program is using sparkjava rest server to build all the http calls. 
	 * Sparkjava is a microframework that uses Jetty webserver module to host 
	 * its' calls. This is a very compact system than using the spring frameworks
	 * that was previously used.
	 * 
	 * There is a custom upnp listener that is started to handle discovery.
	 * 
	 * 
	 */
	public static void main(String[] args) {
		new HABridge().start();
	}

	private boolean running() {
		return !bridgeSettings.getBridgeControl().isStop();
	}

    private void start() {

        log.info("HA Bridge startup sequence...");

    	// sparkjava config directive to set html static file location for Jetty
        while(running()) {
			initialize();
			addShutdownHook();

			UDPDatagramSender udpSender = createUDPDatagramSender();
	        if (udpSender == null) {
	        	bridgeSettings.getBridgeControl().setStop(true);	        	
	        } else {
		        //Setup the device connection homes through the manager
		        homeManager.buildHomes(bridgeSettings, udpSender);
		        // setup the class to handle the resource setup rest api
		        theResources = new DeviceResource(bridgeSettings, homeManager);
		        // setup the class to handle the hue emulator rest api
		        theHueMulator = new HueMulator(bridgeSettings, theResources.getDeviceRepository(), theResources.getGroupRepository(), homeManager);
		        theHueMulator.setupServer();
		        // wait for the sparkjava initialization of the rest api classes to be complete
		        awaitInitialization();

				logUpnpConfig();
				// setup the class to handle the upnp response rest api
		        theSettingResponder = new UpnpSettingsResource(bridgeSettings);
		        theSettingResponder.setupServer();

				startupUpnpSsdpDiscoveryListener(udpSender);
				log.info("Going to close all homes");
		        homeManager.closeHomes();
		        udpSender.closeResponseSocket();
	        }
	        stop();
			sleep();
        }
		shutdown();
	}

	private UDPDatagramSender createUDPDatagramSender(){
		return UDPDatagramSender.createUDPDatagramSender(bridgeSettings.getBridgeSettingsDescriptor().getUpnpResponsePort());
	}

	private void logUpnpConfig() {
		if(bridgeSettings.getBridgeSettingsDescriptor().isTraceupnp()) {
			log.info("Traceupnp: upnp config address: {} -useIface: {} on web server: {}:{}",
					bridgeSettings.getBridgeSettingsDescriptor().getUpnpConfigAddress(),
					bridgeSettings.getBridgeSettingsDescriptor().isUseupnpiface(),
					bridgeSettings.getBridgeSettingsDescriptor().getWebaddress(),
					bridgeSettings.getBridgeSettingsDescriptor().getServerPort());
		}
	}

	private void startupUpnpSsdpDiscoveryListener(UDPDatagramSender udpSender) {
		UpnpListener theUpnpListener = null;
		try {
			theUpnpListener = new UpnpListener(bridgeSettings, bridgeSettings.getBridgeControl(), udpSender);
		} catch (IOException e) {
			log.error("Could not initialize UpnpListener, exiting....", e);
			theUpnpListener = null;
		}
		if(theUpnpListener != null && theUpnpListener.startListening())
			log.info("HA Bridge (v{}) reinitialization requessted....", theVersion.getVersion());
		else
			bridgeSettings.getBridgeControl().setStop(true);
		if(bridgeSettings.getBridgeSettingsDescriptor().isSettingsChanged())
			bridgeSettings.save(bridgeSettings.getBridgeSettingsDescriptor());
	}

	private void shutdown() {
		bridgeSettings.getBridgeSecurity().removeTestUsers();
		if(bridgeSettings.getBridgeSecurity().isSettingsChanged())
			bridgeSettings.updateConfigFile();
		try {
			HttpClientPool.shutdown();
		} catch (InterruptedException e) {
			log.warn("Error shutting down http pool: {}", e.getMessage());;
		} catch (IOException e) {
			log.warn("Error shutting down http pool: {}", e.getMessage());;
		}
		log.info("HA Bridge (v{}) exiting....", theVersion.getVersion());
		System.exit(0);
	}

	private void initialize() {
		log.info("HA Bridge (v{}) initializing....", theVersion.getVersion() );

		bridgeSettings.buildSettings();
		if (bridgeSettings.getBridgeSecurity().isUseHttps()) {
			secure(bridgeSettings.getBridgeSecurity().getKeyfilePath(), bridgeSettings.getBridgeSecurity().getKeyfilePassword(), null, null);
			log.info("Using https for web and api calls");
		}
		bridgeSettings.getBridgeSecurity().removeTestUsers();
		// sparkjava config directive to set ip address for the web server to listen on
		ipAddress(bridgeSettings.getBridgeSettingsDescriptor().getWebaddress());
		// sparkjava config directive to set port for the web server to listen on
		port(bridgeSettings.getBridgeSettingsDescriptor().getServerPort());
		staticFileLocation("/public");
		initExceptionHandler((e) -> HABridge.theExceptionHandler(e, bridgeSettings.getBridgeSettingsDescriptor().getServerPort()));
		if(!bridgeSettings.getBridgeControl().isReinit())
			init();
		bridgeSettings.getBridgeControl().setReinit(false);
		// setup system control api first
		theSystem = new SystemControl(bridgeSettings, theVersion);
		theSystem.setupServer();
	}

	private void sleep() {
		if(running()) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				log.error("Sleep error: {}", e.getMessage());
			}
		}
	}

	private void addShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new ShutdownHook(bridgeSettings, theSystem));
	}
    
    private static void theExceptionHandler(Exception e, Integer thePort) {
		Logger log = LoggerFactory.getLogger(HABridge.class);
		if(e.getMessage().equals("no valid keystore") || e.getMessage().equals("keystore password was incorrect")) {
			log.error("Https settings have been removed as {}. Restart system manually after this process exits....", e.getMessage());
			log.warn(theSystem.removeHttpsSettings());
		}
		else {
			log.error("Could not start ha-bridge webservice on port [{}] due to: {}", thePort, e.getMessage());
			log.warn(theSystem.stop());
		}
	}
}
