package android.media;

import android.os.SystemProperties;
import android.media.AudioFormat;

import java.util.Arrays;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

class AmazonAudioHooks extends BroadcastReceiver {

    private static final String TAG = "AmazonAudioHooks";

    private Context mContext;

    private int mDolbyPassthroughAvailability = -1;

    private int mDolbyPassthroughSupported = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        this.mContext = context;

        int support = 0;
        int[] formats = intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS);
        Log.e(TAG, "AudioManager.EXTRA_ENCODINGS=" + Arrays.toString(formats));
        if (formats != null) {
            for(int format : formats)
                if (format == AudioFormat.ENCODING_AC3) {
                    support = 1;
                    break;
                }

            int[] dolbyFormats = getEncodingFormats();
            Log.e(TAG, "dolbyFormats: " + Arrays.toString(dolbyFormats));
            SystemProperties.set("persist.sys.hdmi.hdmiencodings", Arrays.toString(dolbyFormats));
        }
        setDolbyPassthroughSupported(support);
    }

    private int[] getEncodingFormats() {
        String[] formatsStr = AudioSystem.getParameters("hdmi_encodings").split("=");
        if (formatsStr.length != 2) {
            Log.e(TAG, "Audio sink doesn't have necessary information");
            return null;
        }
        String[] enc_parts = formatsStr[1].split(";");
        int[] formats = new int[enc_parts.length];
        for (int i = 0; i < enc_parts.length; i++) {
            if (enc_parts[i].equals("pcm")) {
                formats[i] = AudioFormat.ENCODING_PCM_16BIT;
            } else if (enc_parts[i].equals("ac3")) {
                formats[i] = AudioFormat.ENCODING_AC3;
            } else if (enc_parts[i].equals("eac3")) {
                formats[i] = AudioFormat.ENCODING_E_AC3;
            }
        }
        return formats;
    }

    private void setDolbyPassthroughSupported(int val) {
        this.mDolbyPassthroughSupported = val;
        int returnVal = getDeviceWithCompressedDolby();
        if (returnVal < 0) {
            Log.e(TAG, "setDolbyPassthroughAvailability(): unrecognized AudioOutputDevice: " + returnVal);
        } else {
            enableAudioFormat(
                AudioOutputDevice.values()[returnVal].ordinal(), 
                AudioOutputFormat.Compressed_Dolby.ordinal());
        }
    }

    private synchronized int getDeviceWithCompressedDolby() {
        return getDolbyDeviceId();
    }

    private int getDolbyDeviceId() {
        Log.d(TAG, "Request to get dolby device id");
        return Settings.Secure.getInt(mContext.getContentResolver(), "amazon_settings_audio_format_compressed_pref", 
                    AudioOutputDevice.Auto_HDMI.ordinal());
    }

    private void setDolbyDeviceId(int outputDeviceOrdinalId) {
        Log.d(TAG, "Storing new dolby device with id: " + outputDeviceOrdinalId);
        Settings.Secure.putInt(mContext.getContentResolver(),
                "amazon_settings_audio_format_compressed_pref", outputDeviceOrdinalId);
        setDolbyPassthroughAvailability();
    }

    private void setDolbyPassthroughAvailability() {
        int dolbyPassthrough;
        Log.d(TAG, "setDolbyPassthroughAvailability()");
        int returnVal = getDeviceWithCompressedDolby();
        if (returnVal < 0) {
            Log.e(TAG, "setDolbyPassthroughAvailability(): unrecognized AudioOutputDevice: " + returnVal);
            return;
        }
        AudioOutputDevice userDolbySetting = AudioOutputDevice.values()[returnVal];
        switch (userDolbySetting) {
            case None:
            case TOSLINK_None:
                dolbyPassthrough = 0;
                break;
            case HDMI:
            case TOSLINK:
            case Dolby_HDMI:
            case Dolby_TOSLINK:
                dolbyPassthrough = 1;
                break;
            case Auto_HDMI:
                dolbyPassthrough = this.mDolbyPassthroughSupported;
                break;
            default:
                Log.e(TAG, "setDolbyPassthroughAvailability(): unrecognized user dolby setting: " + userDolbySetting);
                return;
        }
        Log.d(TAG, "setDolbyPassthroughAvailability(): Value calculated for Dolby passthrough availability: " + dolbyPassthrough);
        if (dolbyPassthrough != this.mDolbyPassthroughAvailability) {
            Log.i(TAG, "setDolbyPassthroughAvailability(): Dolby passthrough availability: " + dolbyPassthrough);
            Settings.Global.putInt(mContext.getContentResolver(), "firetv_hdmi_dolby_passthrough_available", dolbyPassthrough);
            this.mDolbyPassthroughAvailability = dolbyPassthrough;
        }
    }

    private synchronized int enableAudioFormat(int deviceEnumOrdinal, int formatEnumOrdinal) {
        AudioOutputFormat lRequestedFormat;
        AudioOutputDevice lSecondDevice;
        String lkeyValueString;
        int ret;
        Log.e(TAG, "enableAudioFormat with device ordinal: " + deviceEnumOrdinal + " format: " + formatEnumOrdinal);
        String lkeyValueString2 = "";
        AudioOutputDevice lRequestedDevice = AudioOutputDevice.values()[deviceEnumOrdinal];
        if (lRequestedDevice == AudioOutputDevice.Dolby_HDMI || lRequestedDevice == AudioOutputDevice.Dolby_TOSLINK) {
            lRequestedFormat = AudioOutputFormat.Compressed_AC3;
        } else {
            lRequestedFormat = AudioOutputFormat.values()[formatEnumOrdinal];
        }
        if (lRequestedDevice == AudioOutputDevice.Auto_HDMI) {
            lkeyValueString = 
                getKeyValueString(AudioOutputDevice.TOSLINK, AudioOutputFormat.Uncompressed) 
                + getKeyValueString(AudioOutputDevice.HDMI, AudioOutputFormat.Auto);
        } else if (lRequestedDevice == AudioOutputDevice.None) {
            lkeyValueString = getKeyValueString(AudioOutputDevice.HDMI, AudioOutputFormat.Uncompressed);
            lkeyValueString = lkeyValueString 
                + getKeyValueString(AudioOutputDevice.TOSLINK, AudioOutputFormat.Uncompressed);
        } else if (lRequestedDevice == AudioOutputDevice.TOSLINK_None) {
            lkeyValueString = lkeyValueString2 
                + getKeyValueString(AudioOutputDevice.TOSLINK, AudioOutputFormat.Uncompressed);
        } else {
            if (lRequestedDevice == AudioOutputDevice.HDMI || lRequestedDevice == AudioOutputDevice.Dolby_HDMI) {
                lSecondDevice = AudioOutputDevice.TOSLINK;
            } else {
                lSecondDevice = AudioOutputDevice.HDMI;
            }
            if (lRequestedFormat == AudioOutputFormat.Compressed_Dolby) {
                lkeyValueString2 = getKeyValueString(lSecondDevice, AudioOutputFormat.Uncompressed);
            }
            lkeyValueString = lkeyValueString2 + getKeyValueString(lRequestedDevice, lRequestedFormat);
        }
        ret = AudioSystem.setParameters(lkeyValueString);
        Log.e(TAG, "KeyValuePair: " + lkeyValueString + "Status: " + ret);
        if (ret != 0) {
            Log.e(TAG, "Could not apply the parameters. Returned error code: " + ret);
        } else if (lRequestedFormat == AudioOutputFormat.Compressed_Dolby 
                || lRequestedFormat == AudioOutputFormat.Compressed_AC3) {
            setDolbyDeviceId(deviceEnumOrdinal);
        }
        return ret;
    }

    private String getKeyValueString(AudioOutputDevice device, AudioOutputFormat format) {
        return (device.toString() + "_format=") + format.toString() + ";";
    }

    private enum AudioOutputDevice {
        None("none"),
        HDMI(AudioSystem.DEVICE_OUT_HDMI_NAME),
        TOSLINK(AudioSystem.DEVICE_OUT_SPDIF_NAME),
        Auto_HDMI("auto_hdmi"),
        Dolby_HDMI(AudioSystem.DEVICE_OUT_HDMI_NAME),
        Dolby_TOSLINK(AudioSystem.DEVICE_OUT_SPDIF_NAME),
        TOSLINK_None(AudioSystem.DEVICE_OUT_SPDIF_NAME);

        private String mStringId;

        AudioOutputDevice(String id) {
            this.mStringId = id;
        }

        @Override
        public String toString() {
            return this.mStringId;
        }
    }

    private enum AudioOutputFormat {
        Uncompressed(0),
        Compressed_Dolby(1),
        Compressed_AC3(4),
        Auto(5);

        private int mIntTriggerId;

        AudioOutputFormat(int id) {
            this.mIntTriggerId = id;
        }

        @Override
        public String toString() {
            return Integer.toString(this.mIntTriggerId);
        }

        public int getTriggerInt() {
            return this.mIntTriggerId;
        }
    }

}