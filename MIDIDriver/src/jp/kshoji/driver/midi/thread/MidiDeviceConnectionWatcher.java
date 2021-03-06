package jp.kshoji.driver.midi.thread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jp.kshoji.driver.midi.listener.OnMidiDeviceAttachedListener;
import jp.kshoji.driver.midi.listener.OnMidiDeviceDetachedListener;
import jp.kshoji.driver.midi.util.Constants;
import jp.kshoji.driver.midi.util.UsbMidiDeviceUtils;
import jp.kshoji.driver.usb.util.DeviceFilter;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

/**
 * Detects USB MIDI Device Connected
 * stop() method must be called when the application will be destroyed.
 * 
 * @author K.Shoji
 */
public final class MidiDeviceConnectionWatcher {
	private final MidiDeviceConnectionWatchThread thread;
	final HashMap<String, UsbDevice> grantedDeviceMap;

	/**
	 * constructor
	 * 
	 * @param context
	 * @param usbManager
	 * @param deviceAttachedListener
	 * @param deviceDetachedListener
	 */
	public MidiDeviceConnectionWatcher(Context context, UsbManager usbManager, OnMidiDeviceAttachedListener deviceAttachedListener, OnMidiDeviceDetachedListener deviceDetachedListener) {
		grantedDeviceMap = new HashMap<String, UsbDevice>();
		thread = new MidiDeviceConnectionWatchThread(context, usbManager, deviceAttachedListener, deviceDetachedListener);
		thread.start();
	}
	
	public void checkConnectedDevicesImmediately() {
		thread.checkConnectedDevices();
	}
	
	/**
	 * stops the watching thread <br />
	 * <br />
	 * Note: Takes one second until the thread stops.
	 * The device attached / detached events will be noticed until the thread will completely stops.
	 */
	public void stop() {
		thread.stopFlag = true;
		
		// blocks while the thread will stop
		while (thread.isAlive()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// ignore
			}
		}
	}
	
	/**
	 * Broadcast receiver for MIDI device connection granted
	 * 
	 * @author K.Shoji
	 */
	private final class UsbMidiGrantedReceiver extends BroadcastReceiver {
		private static final String USB_PERMISSION_GRANTED_ACTION = "jp.kshoji.driver.midi.USB_PERMISSION_GRANTED_ACTION";
		
		private final String deviceName;
		private final UsbDevice device;
		private final OnMidiDeviceAttachedListener deviceAttachedListener;
		
		/**
		 * @param device
		 * @param deviceAttachedListener
		 */
		public UsbMidiGrantedReceiver(String deviceName, UsbDevice device, OnMidiDeviceAttachedListener deviceAttachedListener) {
			this.deviceName = deviceName;
			this.device = device;
			this.deviceAttachedListener = deviceAttachedListener;
		}
		
		/*
		 * (non-Javadoc)
		 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
		 */
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (USB_PERMISSION_GRANTED_ACTION.equals(action)) {
				boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
				if (granted) {
					if (deviceAttachedListener != null && device != null) {
						grantedDeviceMap.put(deviceName, device);
						deviceAttachedListener.onDeviceAttached(device);
					}
				}
			}
		}
	}
	
	/**
	 * USB Device polling thread
	 * 
	 * @author K.Shoji
	 */
	private final class MidiDeviceConnectionWatchThread extends Thread {
		private Context context;
		private UsbManager usbManager;
		private OnMidiDeviceAttachedListener deviceAttachedListener;
		private OnMidiDeviceDetachedListener deviceDetachedListener;
		private HashSet<String> deviceNameSet;
		boolean stopFlag;

		/**
		 * constructor
		 * 
		 * @param context
		 * @param usbManager
		 * @param deviceAttachedListener
		 * @param deviceDetachedListener
		 */
		MidiDeviceConnectionWatchThread(Context context, UsbManager usbManager, OnMidiDeviceAttachedListener deviceAttachedListener, OnMidiDeviceDetachedListener deviceDetachedListener) {
			this.context = context;
			this.usbManager = usbManager;
			this.deviceAttachedListener = deviceAttachedListener;
			this.deviceDetachedListener = deviceDetachedListener;
			deviceNameSet = new HashSet<String>();
			stopFlag = false;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Thread#run()
		 */
		@Override
		public void run() {
			super.run();
			
			while (stopFlag == false) {
				checkConnectedDevices();
				
				try {
					sleep(1000);
				} catch (InterruptedException e) {
					Log.d(Constants.TAG, "Thread interrupted", e);
				}
			}
		}

		/**
		 * checks Attached/Detached devices
		 */
		synchronized void checkConnectedDevices() {
			HashMap<String, UsbDevice> deviceMap = usbManager.getDeviceList();
			
			// check attached device
			for (String deviceName : deviceMap.keySet()) {
				if (!deviceNameSet.contains(deviceName)) {
					deviceNameSet.add(deviceName);
					UsbDevice device = deviceMap.get(deviceName);
					
					List<DeviceFilter> deviceFilters = DeviceFilter.getDeviceFilters(context);
					Set<UsbInterface> midiInterfaces = UsbMidiDeviceUtils.findAllMidiInterfaces(device, deviceFilters);
					if (midiInterfaces.size() > 0) {
						PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(UsbMidiGrantedReceiver.USB_PERMISSION_GRANTED_ACTION), 0);
						context.registerReceiver(new UsbMidiGrantedReceiver(deviceName, device, deviceAttachedListener), new IntentFilter(UsbMidiGrantedReceiver.USB_PERMISSION_GRANTED_ACTION));
						usbManager.requestPermission(device, permissionIntent);
					}
				}
			}
			
			// check detached device
			List<String> removeDeviceNames = new ArrayList<String>();
			for (String deviceName : deviceNameSet) {
				if (!deviceMap.containsKey(deviceName)) {
					removeDeviceNames.add(deviceName);
					UsbDevice device = grantedDeviceMap.get(deviceName);
					grantedDeviceMap.remove(deviceName);

					Log.d(Constants.TAG, "deviceName:" + deviceName + ", device:" + device + " detached.");
					if (device != null) {
						deviceDetachedListener.onDeviceDetached(device);
					}
				}
			}
			
			for (String deviceName : removeDeviceNames) {
				deviceNameSet.remove(deviceName);
			}
		}
	}
}
