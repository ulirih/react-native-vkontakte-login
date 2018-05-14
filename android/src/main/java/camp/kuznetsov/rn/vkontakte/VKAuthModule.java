package camp.kuznetsov.rn.vkontakte;


import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import com.facebook.react.bridge.*;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.vk.sdk.*;
import com.vk.sdk.api.VKApi;
import com.vk.sdk.api.VKApiConst;
import com.vk.sdk.api.VKError;
import com.vk.sdk.api.VKParameters;
import com.vk.sdk.api.VKRequest;
import com.vk.sdk.api.VKResponse;
import com.vk.sdk.api.methods.VKApiFriends;
import com.vk.sdk.api.model.VKApiUser;
import com.vk.sdk.api.model.VKApiUserFull;
import com.vk.sdk.api.model.VKList;
import com.vk.sdk.api.model.VKUsersArray;
import com.vk.sdk.util.VKUtil;

import org.json.JSONObject;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class VKAuthModule extends ReactContextBaseJavaModule implements ActivityEventListener {
    private static final String LOG = "VKAuthModule";

    private static final String VK_API_VERSION = "5.52";

    private static final String E_ACTIVITY_DOES_NOT_EXIST = "E_ACTIVITY_DOES_NOT_EXIST";
    private static final String E_NOT_INITIALIZED = "E_NOT_INITIALIZED";
    private static final String E_VKSDK_ERROR = "E_VKSDK_ERROR";
    private static final String E_FINGERPRINTS_ERROR = "E_FINGERPRINTS_ERROR";
    private static final String TOKEN_INVALID = "TOKEN_INVALID";
    private static final String M_NOT_INITIALIZED = "VK SDK must be initialized first";

    private Promise loginPromise;
    private boolean isInitialized = false;

    @Override
    public void onNewIntent(Intent intent) {
    }

    public VKAuthModule(final ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addActivityEventListener(this);
        VKAccessTokenTracker vkAccessTokenTracker = new VKAccessTokenTracker() {
            @Override
            public void onVKAccessTokenChanged(VKAccessToken oldToken, VKAccessToken newToken) {
                if (newToken == null) {
                    reactContext
                            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                            .emit(TOKEN_INVALID, null);
                }
            }
        };
        vkAccessTokenTracker.startTracking();
        int appId = 0;
        int resId = reactContext.getResources().getIdentifier(VKSdk.SDK_APP_ID, "integer", reactContext.getPackageName());
        try {
            appId = reactContext.getResources().getInteger(resId);
        } catch (Exception e) {
        }
        Log.d(LOG, "VK AppID found in resources: " + appId);
        if (appId != 0) {
            VKSdk.customInitialize(reactContext, appId, VK_API_VERSION);
            isInitialized = true;
        }
    }

    @Override
    public String getName() {
        return "VkontakteManager";
    }

    @Nullable
    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put(TOKEN_INVALID, TOKEN_INVALID);
        return constants;
    }

    @ReactMethod
    public void initialize(final Integer appId) {
        Log.d(LOG, "Inititalizing " + appId);
        if (appId != 0) {
            VKSdk.customInitialize(getReactApplicationContext(), appId, VK_API_VERSION);
            isInitialized = true;
        } else {
            throw new JSApplicationIllegalArgumentException("VK App Id cannot be 0");
        }
    }

    @ReactMethod
    public void login(final ReadableArray scope, final Promise promise) {
        if (!isInitialized) {
            promise.reject(E_NOT_INITIALIZED, M_NOT_INITIALIZED);
            return;
        }
        Activity activity = getCurrentActivity();

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        int scopeSize = scope.size();
        String[] scopeArray = new String[scopeSize];
        for (int i = 0; i < scopeSize; i++) {
            scopeArray[i] = scope.getString(i);
        }

        if (VKSdk.isLoggedIn() && VKAccessToken.currentToken() != null) {
            boolean hasScope = false;
            try {
                hasScope = VKAccessToken.currentToken().hasScope(scopeArray);
            } catch (Exception e) {
            }

            if (hasScope) {
                Log.d(LOG, "Already logged in with all requested scopes");
                promise.resolve(makeLoginResponse(VKAccessToken.currentToken()));
                return;
            }
        }

        Log.d(LOG, "Requesting scopes (" + scopeSize + ") " + Arrays.toString(scopeArray));
        loginPromise = promise;
        VKSdk.login(activity, scopeArray);
    }

    @ReactMethod
    public void logout(Promise promise) {
        if (!isInitialized) {
            promise.reject(E_NOT_INITIALIZED, M_NOT_INITIALIZED);
            return;
        }
        VKSdk.logout();
        promise.resolve(null);
    }

    @ReactMethod
    public void getFriends(String token, final Promise promise) {
        if (!isInitialized) {
            promise.reject(E_NOT_INITIALIZED, M_NOT_INITIALIZED);
            return;
        }

        final VKRequest request = VKApi.friends().get(VKParameters.from(VKApiConst.ACCESS_TOKEN, token, VKApiConst.FIELDS, "photo_50"));
        request.executeWithListener(new VKRequest.VKRequestListener() {
            @Override
            public void onComplete(VKResponse response) {
                super.onComplete(response);
                try {
                    promise.resolve(makeUserFriendsResponse((VKList<VKApiUserFull>)response.parsedModel));
                } catch (Exception e) {
                    promise.reject(E_VKSDK_ERROR, e.toString());
                }
            }

            @Override
            public void onError(VKError error) {
                super.onError(error);
                promise.reject(E_VKSDK_ERROR, error.toString());
            }
        });
    }

    @ReactMethod
    public void isLoggedIn(Promise promise) {
        if (isInitialized) {
            promise.resolve(VKSdk.isLoggedIn());
        } else {
            promise.reject(E_NOT_INITIALIZED, M_NOT_INITIALIZED);
        }
    }

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        VKSdk.onActivityResult(requestCode, resultCode, data, new VKCallback<VKAccessToken>() {
            @Override
            public void onResult(VKAccessToken res) {
                if (loginPromise != null) {
                    loginPromise.resolve(makeLoginResponse(res));
                    loginPromise = null;
                }
            }

            @Override
            public void onError(VKError error) {
                if (loginPromise != null) {
                    loginPromise.reject(E_VKSDK_ERROR, error.toString());
                    loginPromise = null;
                }
            }
        });
    }

    @ReactMethod
    public void getCertificateFingerprint(Promise promise) {
        try {
            ReactApplicationContext reactContext = getReactApplicationContext();
            String[] fingerprints = VKUtil.getCertificateFingerprint(reactContext, reactContext.getPackageName());
            WritableArray result = Arguments.fromArray(fingerprints);
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject(E_FINGERPRINTS_ERROR, e.toString());
        }

    }

    private WritableArray makeUserFriendsResponse(VKList<VKApiUserFull> users) {
        WritableArray friends = Arguments.createArray();

        for (VKApiUserFull user : users) {
            WritableMap friend = Arguments.createMap();
            friend.putString("id", Integer.toString(user.id));
            friend.putString("first_name", user.first_name);
            friend.putString("last_name", user.last_name);
            friend.putString("photo_50", user.photo_50);

            friends.pushMap(friend);
        }

        return friends;
    }

    private WritableMap makeLoginResponse(VKAccessToken token) {
        WritableMap result = Arguments.createMap();

        result.putString(VKAccessToken.ACCESS_TOKEN, token.accessToken);
        result.putInt(VKAccessToken.EXPIRES_IN, token.expiresIn);
        result.putString(VKAccessToken.USER_ID, token.userId);
        result.putBoolean(VKAccessToken.HTTPS_REQUIRED, token.httpsRequired);
        result.putString(VKAccessToken.SECRET, token.secret);
        result.putString(VKAccessToken.EMAIL, token.email);

        return result;
    }
}
