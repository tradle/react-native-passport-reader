/*
 * Copyright 2016 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.tradle.nfc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;

import net.sf.scuba.smartcards.CardFileInputStream;
import net.sf.scuba.smartcards.CardService;

import org.jmrtd.BACKey;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.COMFile;
import org.jmrtd.lds.CardAccessFile;
import org.jmrtd.lds.DG1File;
import org.jmrtd.lds.DG2File;
import org.jmrtd.lds.FaceImageInfo;
import org.jmrtd.lds.FaceInfo;
import org.jmrtd.lds.LDS;
import org.jmrtd.lds.MRZInfo;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SODFile;
import org.spongycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RNPassportReaderModule extends ReactContextBaseJavaModule implements LifecycleEventListener, ActivityEventListener {

  private static final int SCAN_REQUEST_CODE = 8735738;
  private static final String E_NOT_SUPPORTED = "E_NOT_SUPPORTED";
  private static final String E_NOT_ENABLED = "E_NOT_ENABLED";
  private static final String E_SCAN_CANCELED = "E_SCAN_CANCELED";
  private static final String E_SCAN_FAILED = "E_SCAN_FAILED";
  private static final String E_SCAN_FAILED_DISCONNECT = "E_SCAN_FAILED_DISCONNECT";
  private static final String E_ONE_REQ_AT_A_TIME = "E_ONE_REQ_AT_A_TIME";
//  private static final String E_MISSING_REQUIRED_PARAM = "E_MISSING_REQUIRED_PARAM";
  private static final String KEY_IS_SUPPORTED = "isSupported";
  private static final String KEY_FIRST_NAME = "firstName";
  private static final String KEY_LAST_NAME = "lastName";
  private static final String KEY_GENDER = "gender";
  private static final String KEY_ISSUER = "issuer";
  private static final String KEY_NATIONALITY = "nationality";
  private static final String KEY_PHOTO = "photo";
  private static final String PARAM_DOC_NUM = "documentNumber";
  private static final String PARAM_DOB = "dateOfBirth";
  private static final String PARAM_DOE = "dateOfExpiry";
  private static final String TAG = "passportreader";
  private static final String JPEG_DATA_URI_PREFIX = "data:image/jpeg;base64,";
  
  private static final String EVENT_NFC_PROGRESS = "EVENT_NFC_PROGRESS";
  private static final String NFC_PROGRESS_ACCESS = "access";
  private static final String NFC_PROGRESS_PERSONAL_INFO = "personal-info";
  private static final String NFC_PROGRESS_PHOTO = "photo";
  private static final String NFC_PROGRESS_VERIFICATION = "verification";

  private final ReactApplicationContext reactContext;
  private Promise scanPromise;
  private ReadableMap opts;
  public RNPassportReaderModule(ReactApplicationContext reactContext) {
    super(reactContext);
    Security.insertProviderAt(new BouncyCastleProvider(), 1);

    reactContext.addLifecycleEventListener(this);
    reactContext.addActivityEventListener(this);

    this.reactContext = reactContext;
  }

  @Override
  public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
  }

  @Override
  public void onNewIntent(Intent intent) {
    if (scanPromise == null) return;
    if (!NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) return;

    Tag tag = intent.getExtras().getParcelable(NfcAdapter.EXTRA_TAG);
    if (!Arrays.asList(tag.getTechList()).contains(IsoDep.class.getName())) return;

    BACKeySpec bacKey = new BACKey(
            opts.getString(PARAM_DOC_NUM),
            opts.getString(PARAM_DOB),
            opts.getString(PARAM_DOE)
    );

    new ReadTask(IsoDep.get(tag), bacKey).execute();
  }

  @Override
  public String getName() {
    return "RNPassportReader";
  }

  @Override
  public Map<String, Object> getConstants() {
    final Map<String, Object> constants = new HashMap<>();
    boolean hasNFC = reactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
    constants.put(KEY_IS_SUPPORTED, hasNFC);
    constants.put("EVENT_NFC_PROGRESS", EVENT_NFC_PROGRESS);
    constants.put("NFC_PROGRESS_ACCESS", NFC_PROGRESS_ACCESS);
    constants.put("NFC_PROGRESS_PERSONAL_INFO", NFC_PROGRESS_PERSONAL_INFO);
    constants.put("NFC_PROGRESS_PHOTO", NFC_PROGRESS_PHOTO);
    constants.put("NFC_PROGRESS_VERIFICATION", NFC_PROGRESS_VERIFICATION);
    return constants;
  }

  @ReactMethod
  public void cancel(final Promise promise) {
    if (scanPromise != null) {
      scanPromise.reject(E_SCAN_CANCELED, "canceled");
    }

    resetState();
    promise.resolve(null);
  }

  @ReactMethod
  public void scan(final ReadableMap opts, final Promise promise) {
    NfcAdapter mNfcAdapter = NfcAdapter.getDefaultAdapter(this.reactContext);
    if (mNfcAdapter == null) {
      promise.reject(E_NOT_SUPPORTED, "NFC chip reading not supported");
      return;
    }

    if (!mNfcAdapter.isEnabled()) {
      promise.reject(E_NOT_ENABLED, "NFC chip reading not enabled");
      return;
    }

    if (scanPromise != null) {
      promise.reject(E_ONE_REQ_AT_A_TIME, "Already running a scan");
      return;
    }

    this.opts = opts;
    this.scanPromise = promise;
  }

  private void resetState() {
    scanPromise = null;
    opts = null;
  }

  @Override
  public void onHostDestroy() {
    resetState();
  }

  @Override
  public void onHostResume() {
    NfcAdapter mNfcAdapter = NfcAdapter.getDefaultAdapter(this.reactContext);
    if (mNfcAdapter == null) return;

    Activity activity = getCurrentActivity();
    Intent intent = new Intent(activity.getApplicationContext(), activity.getClass());
    intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pendingIntent = PendingIntent.getActivity(getCurrentActivity(), 0, intent, 0);//PendingIntent.FLAG_UPDATE_CURRENT);
    String[][] filter = new String[][] { new String[] { IsoDep.class.getName()  } };
    mNfcAdapter.enableForegroundDispatch(getCurrentActivity(), pendingIntent, null, filter);
  }

  @Override
  public void onHostPause() {
    NfcAdapter mNfcAdapter = NfcAdapter.getDefaultAdapter(this.reactContext);
    if (mNfcAdapter == null) return;

    mNfcAdapter.disableForegroundDispatch(getCurrentActivity());
  }

  private static String exceptionStack(Throwable exception) {
    StringBuilder s = new StringBuilder();
    String exceptionMsg = exception.getMessage();
    if (exceptionMsg != null) {
      s.append(exceptionMsg);
      s.append(" - ");
    }
    s.append(exception.getClass().getSimpleName());
    StackTraceElement[] stack = exception.getStackTrace();

    if (stack.length > 0) {
      int count = 3;
      boolean first = true;
      boolean skip = false;
      String file = "";
      s.append(" (");
      for (StackTraceElement element : stack) {
        if (count > 0 && element.getClassName().startsWith("io.tradle")) {
          if (!first) {
            s.append(" < ");
          } else {
            first = false;
          }

          if (skip) {
            s.append("... < ");
            skip = false;
          }

          if (file.equals(element.getFileName())) {
            s.append("*");
          } else {
            file = element.getFileName();
            s.append(file.substring(0, file.length() - 5)); // remove ".java"
            count -= 1;
          }
          s.append(":").append(element.getLineNumber());
        } else {
          skip = true;
        }
      }
      if (skip) {
        if (!first) {
          s.append(" < ");
        }
        s.append("...");
      }
      s.append(")");
    }
    return s.toString();
  }

  private static String toBase64(final Bitmap bitmap, final int quality) {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream);
    byte[] byteArray = byteArrayOutputStream.toByteArray();
    return JPEG_DATA_URI_PREFIX + Base64.encodeToString(byteArray, Base64.NO_WRAP);
  }

  private class ReadTask extends AsyncTask<Void, Void, Exception> {

    private IsoDep isoDep;
    private BACKeySpec bacKey;

    public ReadTask(IsoDep isoDep, BACKeySpec bacKey) {
      this.isoDep = isoDep;
      this.bacKey = bacKey;
    }

    private COMFile comFile;
    private SODFile sodFile;
    private DG1File dg1File;
    private DG2File dg2File;

    private Bitmap bitmap;

    @Override
    protected Exception doInBackground(Void... params) {
      try {
        getReactApplicationContext()
                .getJSModule(RCTNativeAppEventEmitter.class)
                .emit(EVENT_NFC_PROGRESS, NFC_PROGRESS_ACCESS);

        CardService cardService = CardService.getInstance(isoDep);
        cardService.open();

        PassportService service = new PassportService(cardService);
        service.open();

        boolean paceSucceeded = false;
        try {
          CardAccessFile cardAccessFile = new CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS));
          Collection<PACEInfo> paceInfos = cardAccessFile.getPACEInfos();
          if (paceInfos != null && paceInfos.size() > 0) {
            PACEInfo paceInfo = paceInfos.iterator().next();
            service.doPACE(bacKey, paceInfo.getObjectIdentifier(), PACEInfo.toParameterSpec(paceInfo.getParameterId()));
            paceSucceeded = true;
          } else {
            paceSucceeded = true;
          }
        } catch (Exception e) {
          Log.w(TAG, e);
        }

        service.sendSelectApplet(paceSucceeded);

        if (!paceSucceeded) {
          try {
            service.getInputStream(PassportService.EF_COM).read();
          } catch (Exception e) {
            service.doBAC(bacKey);
          }
        }

        LDS lds = new LDS();

//        CardFileInputStream comIn = service.getInputStream(PassportService.EF_COM);
//        lds.add(PassportService.EF_COM, comIn, comIn.getLength());
//        comFile = lds.getCOMFile();
//
//        CardFileInputStream sodIn = service.getInputStream(PassportService.EF_SOD);
//        lds.add(PassportService.EF_SOD, sodIn, sodIn.getLength());
//        sodFile = lds.getSODFile();

        CardFileInputStream dg1In = service.getInputStream(PassportService.EF_DG1);
        lds.add(PassportService.EF_DG1, dg1In, dg1In.getLength());
        dg1File = lds.getDG1File();

        CardFileInputStream dg2In = service.getInputStream(PassportService.EF_DG2);
        lds.add(PassportService.EF_DG2, dg2In, dg2In.getLength());
        dg2File = lds.getDG2File();

        getReactApplicationContext()
                .getJSModule(RCTNativeAppEventEmitter.class)
                .emit(EVENT_NFC_PROGRESS, NFC_PROGRESS_PERSONAL_INFO);

        List<FaceImageInfo> allFaceImageInfos = new ArrayList<>();
        List<FaceInfo> faceInfos = dg2File.getFaceInfos();
        for (FaceInfo faceInfo : faceInfos) {
          allFaceImageInfos.addAll(faceInfo.getFaceImageInfos());
        }

        if (!allFaceImageInfos.isEmpty()) {
          FaceImageInfo faceImageInfo = allFaceImageInfos.iterator().next();

          int imageLength = faceImageInfo.getImageLength();
          DataInputStream dataInputStream = new DataInputStream(faceImageInfo.getImageInputStream());
          byte[] buffer = new byte[imageLength];
          dataInputStream.readFully(buffer, 0, imageLength);
          InputStream inputStream = new ByteArrayInputStream(buffer, 0, imageLength);

          bitmap = ImageUtil.decodeImage(reactContext, faceImageInfo.getMimeType(), inputStream);

        }

        getReactApplicationContext()
                .getJSModule(RCTNativeAppEventEmitter.class)
                .emit(EVENT_NFC_PROGRESS, NFC_PROGRESS_PHOTO);
      } catch (Exception e) {
        return e;
      }
      return null;
    }

    @Override
    protected void onPostExecute(Exception result) {
      if (scanPromise == null) return;

      if (result != null) {
        Log.w(TAG, exceptionStack(result));
        if (result instanceof IOException) {
          scanPromise.reject(E_SCAN_FAILED_DISCONNECT, "Lost connection to chip on card");
        } else {
          scanPromise.reject(E_SCAN_FAILED, result);
        }

        resetState();
        return;
      }

      MRZInfo mrzInfo = dg1File.getMRZInfo();

      int quality = 100;
      if (opts.hasKey("quality")) {
        quality = (int)(opts.getDouble("quality") * 100);
      }

      String base64 = toBase64(bitmap, quality);
      WritableMap photo = Arguments.createMap();
      photo.putString("base64", base64);
      photo.putInt("width", bitmap.getWidth());
      photo.putInt("height", bitmap.getHeight());

      String firstName = mrzInfo.getSecondaryIdentifier().replace("<", "");
      String lastName = mrzInfo.getPrimaryIdentifier().replace("<", "");
      WritableMap passport = Arguments.createMap();
      passport.putMap(KEY_PHOTO, photo);
      passport.putString(KEY_FIRST_NAME, firstName);
      passport.putString(KEY_LAST_NAME, lastName);
      passport.putString(KEY_NATIONALITY, mrzInfo.getNationality());
      passport.putString(KEY_GENDER, mrzInfo.getGender().toString());
      passport.putString(KEY_ISSUER, mrzInfo.getIssuingState());

      getReactApplicationContext()
              .getJSModule(RCTNativeAppEventEmitter.class)
              .emit(EVENT_NFC_PROGRESS, NFC_PROGRESS_VERIFICATION);

      scanPromise.resolve(passport);
      resetState();
    }
  }
}
