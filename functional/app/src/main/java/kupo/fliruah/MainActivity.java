package kupo.fliruah;



import android.Manifest;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.flir.flironesdk.Device;
import com.flir.flironesdk.Frame;
import com.flir.flironesdk.FrameProcessor;
import com.flir.flironesdk.RenderedImage;
import com.flir.flironesdk.SimulatedDevice;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.Permission;
import java.text.NumberFormat;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements Device.Delegate, Device.StreamDelegate, FrameProcessor.Delegate,
        Device.PowerUpdateDelegate {

    final public int PERMISSION_SDCARD = 0;

    ImageView thermalView;
    TextView status;
    Device flir;
    FrameProcessor frameProcessor;
    TextView battery;
    TextView batteryState;
    TextView batteryPercentage;
    TextView torch;
    TextView imageType;
    TextView temp;
    ImageView crosshair;
    boolean spotMeterActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        thermalView = (ImageView) findViewById(R.id.thermalView);
        status = (TextView) findViewById(R.id.status);
        battery = (TextView) findViewById(R.id.battery);
        batteryState = (TextView) findViewById(R.id.battery_state);
        batteryPercentage = (TextView) findViewById(R.id.battery_perc);
        torch = (TextView) findViewById(R.id.torch);
        imageType = (TextView) findViewById(R.id.image_type);
        temp = (TextView) findViewById(R.id.temp);
        crosshair = (ImageView)findViewById(R.id.crosshair);

        thermalView.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                LinearLayout l = (LinearLayout)findViewById(R.id.info);
                int visibility = l.getVisibility();
                if(visibility == View.VISIBLE){
                    l.setVisibility(View.GONE);
                }else {
                    l.setVisibility(View.VISIBLE);
                }
                if(spotMeterActive)refreshSpotMeter();
            }
        });

        setUp();

    }


    @Override
    protected void onStart() {
        super.onStart();
        Device.startDiscovery(this, this);
        if (flir != null) flir.startFrameStream(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Device.stopDiscovery();
        if (flir != null) flir.stopFrameStream();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (flir != null) flir.close();
    }

    @Override
    public void onTuningStateChanged(Device.TuningState tuningState) {

    }

    @Override
    public void onAutomaticTuningChanged(boolean b) {

    }

    @Override
    public void onDeviceConnected(Device device) {
        Log.wtf("DEVICE", "connected");
        flir = device;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText("DEVICE connected");
            }
        });
        flir.startFrameStream(this);
        if (flir.hasBattery()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    battery.setText("has battery");
                }
            });
            flir.setPowerUpdateDelegate(this);
        } else {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    battery.setText("has no battery");
                }
            });
        }
        if (flir.hasTorch()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    torch.setText("has torch");
                }
            });
        } else {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    torch.setText("has no torch, use device torch");
                }
            });
        }

    }

    @Override
    public void onDeviceDisconnected(Device device) {
        Log.wtf("DEVICE", "disconnected");
        flir = null;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText("DEVICE disconnected");
            }
        });


    }

    public void onCreateSim(View v) {
        try {
            if (flir == null) {
                flir = new SimulatedDevice(this, this, getResources().openRawResource(R.raw.sampleframes), 1);
                Log.wtf("SIM", "created device");
            } else {
                flir.close();
                flir = null;
            }

        } catch (Exception e) {
            Log.wtf("EXCEPTION", " @creating sim device");
            e.printStackTrace();
        }
    }


    @Override
    public void onFrameReceived(Frame frame) {
        Log.wtf("STREAM DELEGATE", "frame received");
        frameProcessor.processFrame(frame);
    }

    private void setUp() {
        HashMap<Integer, String> imageTypeNames = new HashMap<>();
        // Massage the type names for display purposes and skip any deprecated
        for (Field field : RenderedImage.ImageType.class.getDeclaredFields()) {
            if (field.isEnumConstant() && !field.isAnnotationPresent(Deprecated.class)) {
                RenderedImage.ImageType t = RenderedImage.ImageType.valueOf(field.getName());
                String name = t.name().replaceAll("(RGBA)|(YCbCr)|(8)", "").replaceAll("([a-z])([A-Z])", "$1 $2");
                imageTypeNames.put(t.ordinal(), name);
            }
        }
        String[] imageTypeNameValues = new String[imageTypeNames.size()];
        for (Map.Entry<Integer, String> mapEntry : imageTypeNames.entrySet()) {
            int index = mapEntry.getKey();
            imageTypeNameValues[index] = mapEntry.getValue();
        }

        RenderedImage.ImageType defaultImageType = RenderedImage.ImageType.BlendedMSXRGBA8888Image;
        frameProcessor = new FrameProcessor(getApplicationContext(), this, EnumSet.of(defaultImageType, RenderedImage.ImageType.ThermalRadiometricKelvinImage));
    }

    private Bitmap thermalBitmap = null;

    @Override
    public void onFrameProcessed(RenderedImage renderedImage) {
        //final String s = renderedImage.imageType().toString();
        //runOnUiThread(new Runnable() {
        //    @Override
        //    public void run() {
        //        imageType.setText(s);
        //    }
        //});
        if (renderedImage.imageType() == RenderedImage.ImageType.ThermalRadiometricKelvinImage) {
            // Note: this code is not optimized

            int[] thermalPixels = renderedImage.thermalPixelValues();
            // average the center 9 pixels for the spot meter

            int width = renderedImage.width();
            int height = renderedImage.height();
            int centerPixelIndex = width * (height / 2) + (width / 2);
            int[] centerPixelIndexes = new int[]{
                    centerPixelIndex, centerPixelIndex - 1, centerPixelIndex + 1,
                    centerPixelIndex - width,
                    centerPixelIndex - width - 1,
                    centerPixelIndex - width + 1,
                    centerPixelIndex + width,
                    centerPixelIndex + width - 1,
                    centerPixelIndex + width + 1
            };

            double averageTemp = 0;

            for (int i = 0; i < centerPixelIndexes.length; i++) {
                // Remember: all primitives are signed, we want the unsigned value,
                // we've used renderedImage.thermalPixelValues() to get unsigned values
                int pixelValue = (thermalPixels[centerPixelIndexes[i]]);
                averageTemp += (((double) pixelValue) - averageTemp) / ((double) i + 1);
            }
            double averageC = (averageTemp / 100) - 273.15;
            NumberFormat numberFormat = NumberFormat.getInstance();
            numberFormat.setMaximumFractionDigits(2);
            numberFormat.setMinimumFractionDigits(2);
            final String spotMeterValue = numberFormat.format(averageC) + "°C";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    temp.setText(spotMeterValue);
                }
            });

            // if radiometric is the only type, also show the image
            if (frameProcessor.getImageTypes().size() == 1) {
                // example of a custom colorization, maps temperatures 0-100C to 8-bit gray-scale
                byte[] argbPixels = new byte[width * height * 4];
                final byte aPixValue = (byte) 255;
                for (int p = 0; p < thermalPixels.length; p++) {
                    int destP = p * 4;
                    byte pixValue = (byte) (Math.min(0xff, Math.max(0x00, (thermalPixels[p] - 27315) * (255.0 / 10000.0))));

                    argbPixels[destP + 3] = aPixValue;
                    // red pixel
                    argbPixels[destP] = argbPixels[destP + 1] = argbPixels[destP + 2] = pixValue;
                }
                final Bitmap demoBitmap = Bitmap.createBitmap(width, renderedImage.height(), Bitmap.Config.ARGB_8888);

                demoBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(argbPixels));
                updateThermalView(demoBitmap);
            }
        } else {
            if (thermalBitmap == null) {
                thermalBitmap = renderedImage.getBitmap();
            } else {
                try {
                    renderedImage.copyToBitmap(thermalBitmap);
                } catch (IllegalArgumentException e) {
                    thermalBitmap = renderedImage.getBitmap();
                }
            }

            updateThermalView(thermalBitmap);
        }

    }

    private void updateThermalView(final Bitmap bitmap) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                thermalView.setImageBitmap(bitmap);
            }
        });
    }

    @Override
    public void onBatteryChargingStateReceived(Device.BatteryChargingState batteryChargingState) {
        String s = "";
        if (batteryChargingState == Device.BatteryChargingState.BAD) {
            s = "a valid battery charging state is not available.";
        } else if (batteryChargingState == Device.BatteryChargingState.CHARGING_SMART_PHONE_FAULT_HEAT) {
            s = "charging fault exists but the iPhone is being charged";
        } else if (batteryChargingState == Device.BatteryChargingState.CHARGING_SMART_PHONE_ONLY) {
            s = "the device is in phone-charging-only mode";
        } else if (batteryChargingState == Device.BatteryChargingState.FAULT) {
            s = "an unexpected charging fault occurred (bad battery, etc.)";
        } else if (batteryChargingState == Device.BatteryChargingState.FAULT_BAD_CHARGER) {
            s = "a charging fault occurred due to low current from the charging source";
        } else if (batteryChargingState == Device.BatteryChargingState.FAULT_HEAT) {
            s = "a charging heat fault occurred";
        } else if (batteryChargingState == Device.BatteryChargingState.MANAGED_CHARGING) {
            s = "the battery is charging from external power.";
        } else if (batteryChargingState == Device.BatteryChargingState.MANAGED_CHARGING_ONLY) {
            s = "the device is in charge-only mode";
        } else if (batteryChargingState == Device.BatteryChargingState.NO_CHARGING) {
            s = "the battery is not charging because the device is not connected to an external power supply.";
        }
        final String batteryUpdate = s;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                batteryState.setText(batteryUpdate);
            }
        });
    }

    @Override
    public void onBatteryPercentageReceived(byte b) {
        final byte percentage = b;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                batteryPercentage.setText("" + percentage + "%");
            }
        });
    }

    public void onCreateSpotMeter(View v){
        if(!spotMeterActive){
            refreshSpotMeter();
        }else{
            crosshair.setVisibility(View.GONE);
        }
        spotMeterActive = !spotMeterActive;
    }

    private void refreshSpotMeter() {
        crosshair.setVisibility(View.VISIBLE);
        Bitmap bitmap = Bitmap.createBitmap(crosshair.getWidth(), crosshair.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setStrokeWidth(5f);
        p.setStyle(Paint.Style.STROKE);
        canvas.drawLine((thermalView.getWidth()/2), (thermalView.getHeight()/2)-50
                , (thermalView.getWidth()/2), (thermalView.getHeight()/2)+50, p);
        canvas.drawLine((thermalView.getWidth()/2)-50, (thermalView.getHeight()/2),
                (thermalView.getWidth()/2)+50, (thermalView.getHeight()/2), p);
        canvas.drawCircle(thermalView.getWidth()/2, thermalView.getHeight()/2,20f, p);
        crosshair.setImageDrawable(new BitmapDrawable(bitmap));
    }

    public void onSaveFrame(View v){
        Toast.makeText(this, "homo 1", Toast.LENGTH_SHORT).show();
        Bitmap bitmap = Bitmap.createBitmap(thermalView.getWidth(), thermalView.getHeight(), Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bitmap);
        thermalView.draw(canvas);
        File path = new File("/sdcard/DCIM/FlirUAH");
        String filename = Calendar.getInstance().getTimeInMillis()+".jpeg";
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if(permissionCheck == PackageManager.PERMISSION_GRANTED){
            writeFile(path, filename, bitmap);
        }else{
            if(ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)){
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                Toast.makeText(this, "homo 2", Toast.LENGTH_SHORT).show();
                builder.setTitle("WRITE PERMISSION").setMessage("Rationale").setPositiveButton("ok",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ActivityCompat.requestPermissions(getParent(), new String[]{
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_SDCARD);
                            }
                        });
            }
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_SDCARD);
            Toast.makeText(this, "homo 3", Toast.LENGTH_SHORT).show();
        }


    }

    public void writeFile(File path, String filename, Bitmap bitmap){
        path.mkdirs();
        try {
            OutputStream out = new FileOutputStream(new File(path, filename));
            bitmap.compress(Bitmap.CompressFormat.JPEG,95, out);
            out.flush();
            out.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults){
        switch (requestCode){
            case PERMISSION_SDCARD:
                if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                    Bitmap bitmap = Bitmap.createBitmap(thermalView.getWidth(), thermalView.getHeight(), Bitmap.Config.ALPHA_8);
                    Canvas canvas = new Canvas(bitmap);
                    thermalView.draw(canvas);
                    File path = new File("/sdcard/FLIRUAH");
                    String filename = Calendar.getInstance().getTimeInMillis()+".jpeg";
                    writeFile(path, filename, bitmap);
                    Log.wtf("PERMISSION", "granted");
                }else{
                    //FUCK
                    Log.wtf("PERMISSION", "not granted");
                }
                return;
        }
    }

    
}