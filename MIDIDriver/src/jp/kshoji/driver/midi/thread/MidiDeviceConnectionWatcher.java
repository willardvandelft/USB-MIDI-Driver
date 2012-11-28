package jp.kshoji.driver.midi.thread;

import java.util.HashMap;
import java.util.HashSet;

import jp.kshoji.driver.midi.listener.OnMidiDeviceAttachedListener;
import jp.kshoji.driver.midi.listener.OnMidiDeviceDetachedListener;
import jp.kshoji.driver.midi.util.Constants;
import jp.kshoji.driver.midi.util.UsbDeviceUtils;
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
 * 
 * @author K.Shoji
 */
public class MidiDeviceConnectionWatcher {
	private MidiDeviceConnectionWatchThread thread;
	HashMap<String, UsbDevice> grantedDeviceMap;

	/**
	 * Constructor
	 * 
	 * @param context
	 * @param deviceAttachedListener
	 * @param deviceDetachedListener
	 */
	public MidiDeviceConnectionWatcher(Context context, OnMidiDeviceAttachedListener deviceAttachedListener, OnMidiDeviceDetachedListener deviceDetachedListener) {
		grantedDeviceMap = new HashMap<String, UsbDevice>();
		thread = new MidiDeviceConnectionWatchThread(context, deviceAttachedListener, deviceDetachedListener);
	}
	
	public final void checkConnectedDevicesImmediately() {
		thread.checkConnectedDevices();
	}

	public final void start() {
		thread.start();
	}
	
	public final void stop() {
		thread.stopFlag = true;
	}
	
	/**
	 * Broadcast receiver for MIDI device connection granted
	 * 
	 * @author K.Shoji
	 */
	class UsbMidiGrantedReceiver extends BroadcastReceiver {
		public static final String USB_PERMISSION_GRANTED_ACTION = "jp.kshoji.driver.midi.USB_PERMISSION_GRANTED_ACTION";
		
		private String deviceName;
		private UsbDevice device;
		private OnMidiDeviceAttachedListener onDeviceAttachedListener;
		
		/**
		 * @param device
		 * @param onMidiDeviceAttachedListener
		 */
		public UsbMidiGrantedReceiver(String deviceName, UsbDevice device, OnMidiDeviceAttachedListener onMidiDeviceAttachedListener) {
			this.deviceName = deviceName;
			this.device = device;
			onDeviceAttachedListener = onMidiDeviceAttachedListener;
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
					if (onDeviceAttachedListener != null && device != null) {
						grantedDeviceMap.put(deviceName, device);
						onDeviceAttachedListener.onDeviceAttached(device);
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
	class MidiDeviceConnectionWatchThread extends Thread {
		private Context context;
		private UsbManager usbManager;
		private OnMidiDeviceAttachedListener deviceAttachedListener;
		private OnMidiDeviceDetachedListener deviceDetachedListener;
		private HashSet<String> deviceNameSet;
		boolean stopFlag;
		
		MidiDeviceConnectionWatchThread(Context context, OnMidiDeviceAttachedListener deviceAttachedListener, OnMidiDeviceDetachedListener deviceDetachedListener) {
			this.context = context;
			usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
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
					
					UsbInterface midiInterface = UsbDeviceUtils.findMidiInterface(device);
					if (midiInterface != null) {
						PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(UsbMidiGrantedReceiver.USB_PERMISSION_GRANTED_ACTION), 0);
						context.registerReceiver(new UsbMidiGrantedReceiver(deviceName, device, deviceAttachedListener), new IntentFilter(UsbMidiGrantedReceiver.USB_PERMISSION_GRANTED_ACTION));
						usbManager.requestPermission(device, permissionIntent);
					}
				}
			}
			
			// check detached device
			for (String deviceName : deviceNameSet) {
				if (!deviceMap.containsKey(deviceName)) {
					deviceNameSet.remove(deviceName);
					UsbDevice device = grantedDeviceMap.get(deviceName);
					grantedDeviceMap.remove(deviceName);

					Log.d(Constants.TAG, "deviceName:" + deviceName + ", device:" + device + " detached.");
					if (device != null) {
						deviceDetachedListener.onDeviceDetached(device);
					}
				}
			}
		}
	}
}