package com.getcapacitor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.util.PermissionHelper;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;

/**
 * Plugin is the base class for all plugins, containing a number of
 * convenient features for interacting with the {@link Bridge}, managing
 * plugin permissions, tracking lifecycle events, and more.
 *
 * You should inherit from this class when creating new plugins, along with
 * adding the {@link CapacitorPlugin} annotation to add additional required
 * metadata about the Plugin
 */
public class Plugin {

    // The key we will use inside of a persisted Bundle for the JSON blob
    // for a plugin call options.
    private static final String BUNDLE_PERSISTED_OPTIONS_JSON_KEY = "_json";

    // Reference to the Bridge
    protected Bridge bridge;

    // Reference to the PluginHandle wrapper for this Plugin
    protected PluginHandle handle;

    /**
     * A way for plugins to quickly save a call that they will need to reference
     * between activity/permissions starts/requests
     *
     * @deprecated store calls on the bridge using the methods
     * {@link com.getcapacitor.Bridge#saveCall(PluginCall)},
     * {@link com.getcapacitor.Bridge#getSavedCall(String)} and
     * {@link com.getcapacitor.Bridge#releaseCall(PluginCall)}
     */
    @Deprecated
    protected PluginCall savedLastCall;

    // Stored event listeners
    private final Map<String, List<PluginCall>> eventListeners;

    /**
     * Base activity result launcher used by the {@link #requestPermissions(PluginCall)} plugin call
     */
    private ActivityResultLauncher<String[]> basePermissionLauncher = null;

    /**
     * Launchers used by the plugin to request permissions
     */
    private final Map<String, ActivityResultLauncher<String[]>> permissionLaunchers = new HashMap<>();

    // Stored results of an event if an event was fired and
    // no listeners were attached yet. Only stores the last value.
    private final Map<String, JSObject> retainedEventArguments;

    public Plugin() {
        eventListeners = new HashMap<>();
        retainedEventArguments = new HashMap<>();
    }

    /**
     * Called when the plugin has been connected to the bridge
     * and is ready to start initializing.
     */
    public void load() {}

    /**
     * Registers the base permission launcher used by the {@link #requestPermissions(PluginCall)} plugin call
     */
    void initializePermissionLaunchers() {
        basePermissionLauncher =
            bridge
                .getActivity()
                .registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    permissions -> {
                        PluginCall call = bridge.getPermissionCall(handle.getId());
                        checkPermissions(call);
                    }
                );

        for (final Method method : getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(PluginMethod.class)) {
                PluginMethod pluginAnnotation = method.getAnnotation(PluginMethod.class);
                if (pluginAnnotation == null) {
                    continue;
                }

                // get the defined permission callback, skip if default (empty string)
                String permResponseMethodName = pluginAnnotation.permissionCallback();
                if (permResponseMethodName.isEmpty()) {
                    continue;
                }

                try {
                    Method permResponseMethod = getClass().getDeclaredMethod(permResponseMethodName, PluginCall.class, Map.class);

                    if (permResponseMethod != null) {
                        permissionLaunchers.put(
                            method.getName(),
                            bridge
                                .getActivity()
                                .registerForActivityResult(
                                    new ActivityResultContracts.RequestMultiplePermissions(),
                                    permissions -> {
                                        PluginCall savedPermissionCall = bridge.getPermissionCall(handle.getId());

                                        if (bridge.validatePermissions(this, savedPermissionCall, permissions)) {
                                            // handle request permissions call
                                            try {
                                                permResponseMethod.setAccessible(true);
                                                permResponseMethod.invoke(this, savedPermissionCall, getPermissionStates());
                                            } catch (IllegalAccessException | InvocationTargetException e) {
                                                e.printStackTrace();
                                            }

                                            if (!savedPermissionCall.isReleased() && !savedPermissionCall.isSaved()) {
                                                savedPermissionCall.release(bridge);
                                            }
                                        }
                                    }
                                )
                        );
                    }
                } catch (NoSuchMethodException e) {
                    Logger.error(
                        String.format(
                            "No method found by the name %s to register as a permission handler. " +
                            "Please check that it exists and has the correct signature: " +
                            "(PluginCall, Map<String, PermissionState>)",
                            permResponseMethodName
                        )
                    );

                    // if the provided method name is not a valid permission handling method, default to base method
                    permissionLaunchers.put(method.getName(), basePermissionLauncher);
                }
            }
        }
    }

    /**
     * Get the main {@link Context} for the current Activity (your app)
     * @return the Context for the current activity
     */
    public Context getContext() {
        return this.bridge.getContext();
    }

    /**
     * Get the main {@link Activity} for the app
     * @return the Activity for the current app
     */
    public AppCompatActivity getActivity() {
        return this.bridge.getActivity();
    }

    /**
     * Set the Bridge instance for this plugin
     * @param bridge
     */
    public void setBridge(Bridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Get the Bridge instance for this plugin
     */
    public Bridge getBridge() {
        return this.bridge;
    }

    /**
     * Set the wrapper {@link PluginHandle} instance for this plugin that
     * contains additional metadata about the Plugin instance (such
     * as indexed methods for reflection, and {@link CapacitorPlugin} annotation data).
     * @param pluginHandle
     */
    public void setPluginHandle(PluginHandle pluginHandle) {
        this.handle = pluginHandle;
    }

    /**
     * Return the wrapper {@link PluginHandle} for this plugin.
     *
     * This wrapper contains additional metadata about the plugin instance,
     * such as indexed methods for reflection, and {@link CapacitorPlugin} annotation data).
     * @return
     */
    public PluginHandle getPluginHandle() {
        return this.handle;
    }

    /**
     * Get the root App ID
     * @return
     */
    public String getAppId() {
        return getContext().getPackageName();
    }

    /**
     * Called to save a {@link PluginCall} in order to reference it
     * later, such as in an activity or permissions result handler
     * @deprecated use {@link Bridge#saveCall(PluginCall)}
     *
     * @param lastCall
     */
    @Deprecated
    public void saveCall(PluginCall lastCall) {
        this.savedLastCall = lastCall;
    }

    /**
     * Set the last saved call to null to free memory
     * @deprecated use {@link PluginCall#release(Bridge)}
     */
    @Deprecated
    public void freeSavedCall() {
        if (!this.savedLastCall.isReleased()) {
            this.savedLastCall.release(bridge);
        }
        this.savedLastCall = null;
    }

    /**
     * Get the last saved call, if any
     * @deprecated use {@link Bridge#getSavedCall(String)}
     *
     * @return
     */
    @Deprecated
    public PluginCall getSavedCall() {
        return this.savedLastCall;
    }

    /**
     * Get the config options for this plugin.
     *
     * @return a config object representing the plugin config options, or an empty config
     * if none exists
     */
    public PluginConfig getConfig() {
        return bridge.getConfig().getPluginConfiguration(handle.getId());
    }

    /**
     * Get the value for a key on the config for this plugin.
     * @deprecated use {@link #getConfig()} and access config values using the methods available
     * depending on the type.
     *
     * @param key the key for the config value
     * @return some object containing the value from the config
     */
    @Deprecated
    public Object getConfigValue(String key) {
        try {
            PluginConfig pluginConfig = getConfig();
            return pluginConfig.getConfigJSON().get(key);
        } catch (JSONException ex) {
            return null;
        }
    }

    /**
     * Check whether any of the given permissions has been defined in the AndroidManifest.xml
     * @param permissions
     * @return
     */
    public boolean hasDefinedPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (!PermissionHelper.hasDefinedPermission(getContext(), permission)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check whether any of the given permissions has been defined in the AndroidManifest.xml
     * @param permissions
     * @return
     */
    public boolean hasDefinedPermissions(Permission[] permissions) {
        for (Permission perm : permissions) {
            for (String permString : perm.strings()) {
                if (!PermissionHelper.hasDefinedPermission(getContext(), permString)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Check if all annotated permissions have been defined in the AndroidManifest.xml
     *
     * @return true if permissions are all defined in the Manifest
     */
    public boolean hasDefinedRequiredPermissions() {
        CapacitorPlugin annotation = handle.getPluginAnnotation();
        if (annotation == null) {
            // Check for legacy plugin annotation, @NativePlugin
            NativePlugin legacyAnnotation = handle.getLegacyPluginAnnotation();
            return hasDefinedPermissions(legacyAnnotation.permissions());
        }

        return hasDefinedPermissions(annotation.permissions());
    }

    /**
     * Check whether the given permission has been granted by the user
     * @param permission
     * @return
     */
    public boolean hasPermission(String permission) {
        return ActivityCompat.checkSelfPermission(this.getContext(), permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * If the {@link CapacitorPlugin} annotation specified a set of permissions,
     * this method checks if each is granted. Note: if you are okay
     * with a limited subset of the permissions being granted, check
     * each one individually instead with hasPermission
     * @return
     */
    public boolean hasRequiredPermissions() {
        CapacitorPlugin annotation = handle.getPluginAnnotation();
        if (annotation == null) {
            // Check for legacy plugin annotation, @NativePlugin
            NativePlugin legacyAnnotation = handle.getLegacyPluginAnnotation();
            for (String perm : legacyAnnotation.permissions()) {
                if (!hasPermission(perm)) {
                    return false;
                }
            }

            return true;
        }

        for (Permission perm : annotation.permissions()) {
            for (String permString : perm.strings()) {
                if (!hasPermission(permString)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Request all of the specified permissions in the CapacitorPlugin annotation (if any)
     *
     * If there is no registered permission callback for the PluginCall passed in, the call will
     * be rejected. Make sure a valid permission callback method is registered using the
     * {@link PluginMethod#permissionCallback()} annotation.
     *
     * @since 3.0.0
     * @param call the plugin call
     */
    protected void requestAllPermissions(@NonNull PluginCall call) {
        String callMethodName = call.getMethodName();
        ActivityResultLauncher<String[]> activityResultLauncher = getLauncherOrReject(call, callMethodName);
        if (activityResultLauncher == null) {
            // return when null since call was rejected in getLauncherOrReject
            return;
        }

        CapacitorPlugin annotation = handle.getPluginAnnotation();
        if (annotation != null) {
            HashSet<String> perms = new HashSet<>();
            for (Permission perm : annotation.permissions()) {
                perms.addAll(Arrays.asList(perm.strings()));
            }

            bridge.savePermissionCall(call);
            activityResultLauncher.launch(perms.toArray(new String[0]));
        }
    }

    /**
     * Request permissions using an alias defined on the plugin.
     *
     * If there is no registered permission callback for the PluginCall passed in, the call will
     * be rejected. Make sure a valid permission callback method is registered using the
     * {@link PluginMethod#permissionCallback()} annotation.
     *
     * @param alias an alias defined on the plugin
     * @param call the plugin call involved in originating the request
     */
    protected void requestPermissionForAlias(@NonNull String alias, @NonNull PluginCall call) {
        requestPermissionForAliases(new String[] { alias }, call);
    }

    /**
     * Request permissions using aliases defined on the plugin.
     *
     * If there is no registered permission callback for the PluginCall passed in, the call will
     * be rejected. Make sure a valid permission callback method is registered using the
     * {@link PluginMethod#permissionCallback()} annotation.
     *
     * @param aliases a set of aliases defined on the plugin
     * @param call the plugin call involved in originating the request
     */
    protected void requestPermissionForAliases(@NonNull String[] aliases, @NonNull PluginCall call) {
        String callMethodName = call.getMethodName();
        ActivityResultLauncher<String[]> activityResultLauncher = getLauncherOrReject(call, callMethodName);
        if (activityResultLauncher == null) {
            // return when null since call was rejected in getLauncherOrReject
            return;
        }

        if (aliases.length == 0) {
            Logger.error("No permission alias was provided");
            return;
        }

        requestPermissionForAliases(aliases, call, activityResultLauncher);
    }

    /**
     * Request permissions using aliases defined on the plugin with a provided activityResultLauncher.
     * Plugin authors should use {@link #requestPermissionForAliases(String[], PluginCall)} with
     * a registered callback method for typical permission request use.
     */
    private void requestPermissionForAliases(
        @NonNull String[] aliases,
        @NonNull PluginCall call,
        ActivityResultLauncher<String[]> activityResultLauncher
    ) {
        String[] permissions = getPermissionStringsForAliases(aliases);

        if (permissions.length > 0) {
            bridge.savePermissionCall(call);
            activityResultLauncher.launch(permissions);
        }
    }

    /**
     * Gets the Android permission strings defined on the {@link CapacitorPlugin} annotation with
     * the provided aliases.
     *
     * @param aliases aliases for permissions defined on the plugin
     * @return Android permission strings associated with the provided aliases, if exists
     */
    private String[] getPermissionStringsForAliases(@NonNull String[] aliases) {
        CapacitorPlugin annotation = handle.getPluginAnnotation();
        HashSet<String> perms = new HashSet<>();
        for (Permission perm : annotation.permissions()) {
            if (Arrays.asList(aliases).contains(perm.alias())) {
                perms.addAll(Arrays.asList(perm.strings()));
            }
        }

        return perms.toArray(new String[0]);
    }

    /**
     * Gets the permission launcher associated with the calling methodName, or rejects the call if
     * no registered launcher exists
     *
     * @param call the plugin call
     * @param methodName the name of the plugin method requesting a permission
     * @return a launcher, or null if none found
     */
    private @Nullable ActivityResultLauncher<String[]> getLauncherOrReject(PluginCall call, String methodName) {
        ActivityResultLauncher<String[]> activityResultLauncher = permissionLaunchers.get(methodName);

        // if there is no registered result launcher but the method is the default requestPermissions
        // method, associate the base permission launcher to make sure states are returned
        if (activityResultLauncher == null && methodName.equals("requestPermissions")) {
            activityResultLauncher = basePermissionLauncher;
        }

        // if there is no registered launcher, reject the call with an error and return null
        if (activityResultLauncher == null) {
            String registerError =
                "There is no permission callback method registered for the plugin method %s. " +
                "Please define a permissionCallback method name in the annotation and provide a " +
                "method that has the correct signature: (PluginCall, Map<String, PermissionState>)";
            registerError = String.format(Locale.US, registerError, methodName);
            Logger.error(registerError);
            call.reject(registerError);
            return null;
        }

        return activityResultLauncher;
    }

    /**
     * Helper for requesting specific permissions
     * @deprecated use {@link #requestPermissions(PluginCall)} in conjunction with @CapacitorPlugin
     *
     * @param permissions the set of permissions to request
     * @param requestCode the requestCode to use to associate the result with the plugin
     */
    @Deprecated
    public void pluginRequestPermissions(String[] permissions, int requestCode) {
        ActivityCompat.requestPermissions(getActivity(), permissions, requestCode);
    }

    /**
     * Request all of the specified permissions in the CapacitorPlugin annotation (if any)
     * @deprecated use {@link #requestAllPermissions(PluginCall)} in conjunction with @CapacitorPlugin
     */
    @Deprecated
    public void pluginRequestAllPermissions() {
        NativePlugin legacyAnnotation = handle.getLegacyPluginAnnotation();
        ActivityCompat.requestPermissions(getActivity(), legacyAnnotation.permissions(), legacyAnnotation.permissionRequestCode());
    }

    /**
     * Helper for requesting a specific permission
     * @deprecated use {@link #requestPermissionForAlias(String, PluginCall)} in conjunction with @CapacitorPlugin
     *
     * @param permission the permission to request
     * @param requestCode the requestCode to use to associate the result with the plugin
     */
    @Deprecated
    public void pluginRequestPermission(String permission, int requestCode) {
        ActivityCompat.requestPermissions(getActivity(), new String[] { permission }, requestCode);
    }

    /**
     * Helper to check all permissions defined on a plugin and see the state of each.
     *
     * @since 3.0.0
     * @return A mapping of permission aliases to the associated granted status.
     */
    public Map<String, PermissionState> getPermissionStates() {
        return bridge.getPermissionStates(this);
    }

    /**
     * Add a listener for the given event
     * @param eventName
     * @param call
     */
    private void addEventListener(String eventName, PluginCall call) {
        List<PluginCall> listeners = eventListeners.get(eventName);
        if (listeners == null || listeners.isEmpty()) {
            listeners = new ArrayList<>();
            eventListeners.put(eventName, listeners);

            // Must add the call before sending retained arguments
            listeners.add(call);

            sendRetainedArgumentsForEvent(eventName);
        } else {
            listeners.add(call);
        }
    }

    /**
     * Remove a listener from the given event
     * @param eventName
     * @param call
     */
    private void removeEventListener(String eventName, PluginCall call) {
        List<PluginCall> listeners = eventListeners.get(eventName);
        if (listeners == null) {
            return;
        }

        listeners.remove(call);
    }

    /**
     * Notify all listeners that an event occurred
     * @param eventName
     * @param data
     */
    protected void notifyListeners(String eventName, JSObject data, boolean retainUntilConsumed) {
        Logger.verbose(getLogTag(), "Notifying listeners for event " + eventName);
        List<PluginCall> listeners = eventListeners.get(eventName);
        if (listeners == null || listeners.isEmpty()) {
            Logger.debug(getLogTag(), "No listeners found for event " + eventName);
            if (retainUntilConsumed) {
                retainedEventArguments.put(eventName, data);
            }
            return;
        }

        for (PluginCall call : listeners) {
            call.resolve(data);
        }
    }

    /**
     * Notify all listeners that an event occurred
     * This calls {@link Plugin#notifyListeners(String, JSObject, boolean)}
     * with retainUntilConsumed set to false
     * @param eventName
     * @param data
     */
    protected void notifyListeners(String eventName, JSObject data) {
        notifyListeners(eventName, data, false);
    }

    /**
     * Check if there are any listeners for the given event
     */
    protected boolean hasListeners(String eventName) {
        List<PluginCall> listeners = eventListeners.get(eventName);
        if (listeners == null) {
            return false;
        }
        return !listeners.isEmpty();
    }

    /**
     * Send retained arguments (if any) for this event. This
     * is called only when the first listener for an event is added
     * @param eventName
     */
    private void sendRetainedArgumentsForEvent(String eventName) {
        JSObject retained = retainedEventArguments.get(eventName);
        if (retained == null) {
            return;
        }

        notifyListeners(eventName, retained);
        retainedEventArguments.remove(eventName);
    }

    /**
     * Exported plugin call for adding a listener to this plugin
     * @param call
     */
    @SuppressWarnings("unused")
    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    public void addListener(PluginCall call) {
        String eventName = call.getString("eventName");
        call.save();
        addEventListener(eventName, call);
    }

    /**
     * Exported plugin call to remove a listener from this plugin
     * @param call
     */
    @SuppressWarnings("unused")
    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    public void removeListener(PluginCall call) {
        String eventName = call.getString("eventName");
        String callbackId = call.getString("callbackId");
        PluginCall savedCall = bridge.getSavedCall(callbackId);
        if (savedCall != null) {
            removeEventListener(eventName, savedCall);
            bridge.releaseCall(savedCall);
        }
    }

    /**
     * Exported plugin call to remove all listeners from this plugin
     * @param call
     */
    @SuppressWarnings("unused")
    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    public void removeAllListeners(PluginCall call) {
        eventListeners.clear();
    }

    /**
     * Exported plugin call for checking the granted status for each permission
     * declared on the plugin. This plugin call responds with a mapping of permissions to
     * the associated granted status.
     *
     * @since 3.0.0
     */
    @PluginMethod
    public void checkPermissions(PluginCall pluginCall) {
        Map<String, PermissionState> permissionsResult = getPermissionStates();

        if (permissionsResult.size() == 0) {
            // if no permissions are defined on the plugin, resolve undefined
            pluginCall.resolve();
        } else {
            JSObject permissionsResultJSON = new JSObject();
            for (Map.Entry<String, PermissionState> entry : permissionsResult.entrySet()) {
                permissionsResultJSON.put(entry.getKey(), entry.getValue());
            }

            pluginCall.resolve(permissionsResultJSON);
        }
    }

    /**
     * Exported plugin call to request all permissions for this plugin.
     * To manually request permissions within a plugin use:
     *  {@link #requestAllPermissions(PluginCall)}, or
     *  {@link #requestPermissionForAlias(String, PluginCall)}, or
     *  {@link #requestPermissionForAliases(String[], PluginCall)}
     *
     * @param call the plugin call
     */
    @PluginMethod
    public void requestPermissions(PluginCall call) {
        CapacitorPlugin annotation = handle.getPluginAnnotation();
        if (annotation == null) {
            // handle permission requests for plugins defined with @NativePlugin (prior to 3.0.0)
            NativePlugin legacyAnnotation = this.handle.getLegacyPluginAnnotation();
            String[] perms = legacyAnnotation.permissions();
            if (perms.length > 0) {
                saveCall(call);
                pluginRequestPermissions(perms, legacyAnnotation.permissionRequestCode());
            } else {
                call.resolve();
            }
        } else {
            // handle permission requests for plugins defined with @CapacitorPlugin (since 3.0.0)
            String[] permAliases = null;
            Set<String> autoGrantPerms = new HashSet<>();

            // If call was made with a list of specific permission aliases to request, save them
            // to be requested
            JSArray providedPerms = call.getArray("permissions");
            List<String> providedPermsList = null;

            try {
                providedPermsList = providedPerms.toList();
            } catch (JSONException ignore) {
                // do nothing
            }

            // If call was made without any custom permissions, request all from plugin annotation
            Set<String> aliasSet = new HashSet<>();
            if (providedPermsList == null || providedPermsList.isEmpty()) {
                for (Permission perm : annotation.permissions()) {
                    // If a permission is defined with no permission strings, separate it for auto-granting.
                    // Otherwise, the alias is added to the list to be requested.
                    if (perm.strings().length == 0 || (perm.strings().length == 1 && perm.strings()[0].isEmpty())) {
                        if (!perm.alias().isEmpty()) {
                            autoGrantPerms.add(perm.alias());
                        }
                    } else {
                        aliasSet.add(perm.alias());
                    }
                }

                permAliases = aliasSet.toArray(new String[0]);
            } else {
                for (Permission perm : annotation.permissions()) {
                    if (providedPermsList.contains(perm.alias())) {
                        aliasSet.add(perm.alias());
                    }
                }

                if (aliasSet.isEmpty()) {
                    call.reject("No valid permission alias was requested of this plugin.");
                } else {
                    permAliases = aliasSet.toArray(new String[0]);
                }
            }

            if (permAliases != null && permAliases.length > 0) {
                // request permissions using provided aliases or all defined on the plugin
                requestPermissionForAliases(permAliases, call, basePermissionLauncher);
            } else if (!autoGrantPerms.isEmpty()) {
                // if the plugin only has auto-grant permissions, return all as GRANTED
                JSObject permissionsResults = new JSObject();

                for (String perm : autoGrantPerms) {
                    permissionsResults.put(perm, PermissionState.GRANTED.toString());
                }

                call.resolve(permissionsResults);
            } else {
                // no permissions are defined on the plugin, resolve undefined
                call.resolve();
            }
        }
    }

    /**
     * Handle request permissions result. A plugin using the deprecated {@link NativePlugin}
     * should override this to handle the result, or this method will handle the result
     * for our convenient requestPermissions call.
     * @deprecated in favor of using callbacks in conjunction with {@link CapacitorPlugin}
     *
     * @param requestCode
     * @param permissions
     * @param grantResults
     */
    @Deprecated
    protected void handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!hasDefinedPermissions(permissions)) {
            StringBuilder builder = new StringBuilder();
            builder.append("Missing the following permissions in AndroidManifest.xml:\n");
            String[] missing = PermissionHelper.getUndefinedPermissions(getContext(), permissions);
            for (String perm : missing) {
                builder.append(perm + "\n");
            }
            savedLastCall.reject(builder.toString());
            savedLastCall = null;
        }
    }

    /**
     * Called before the app is destroyed to give a plugin the chance to
     * save the last call options for a saved plugin. By default, this
     * method saves the full JSON blob of the options call. Since Bundle sizes
     * may be limited, plugins that expect to be called with large data
     * objects (such as a file), should override this method and selectively
     * store option values in a {@link Bundle} to avoid exceeding limits.
     * @return a new {@link Bundle} with fields set from the options of the last saved {@link PluginCall}
     */
    protected Bundle saveInstanceState() {
        PluginCall savedCall = getSavedCall();

        if (savedCall == null) {
            return null;
        }

        Bundle ret = new Bundle();
        JSObject callData = savedCall.getData();

        if (callData != null) {
            ret.putString(BUNDLE_PERSISTED_OPTIONS_JSON_KEY, callData.toString());
        }

        return ret;
    }

    /**
     * Called when the app is opened with a previously un-handled
     * activity response. If the plugin that started the activity
     * stored data in {@link Plugin#saveInstanceState()} then this
     * method will be called to allow the plugin to restore from that.
     * @param state
     */
    protected void restoreState(Bundle state) {}

    /**
     * Handle activity result, should be overridden by each plugin
     * @param lastPluginCall
     * @param requestCode
     * @param resultCode
     * @param data
     */
    protected void handleOnActivityResult(PluginCall lastPluginCall, int requestCode, int resultCode, Intent data) {}

    /**
     * Handle activity result, should be overridden by each plugin
     * @deprecated use {@link #handleOnActivityResult(PluginCall, int, int, Intent)} in
     * conjunction with @CapacitorPlugin
     *
     * @param requestCode
     * @param resultCode
     * @param data
     */
    @Deprecated
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {}

    /**
     * Handle onNewIntent
     * @param intent
     */
    protected void handleOnNewIntent(Intent intent) {}

    /**
     * Handle onConfigurationChanged
     * @param newConfig
     */
    protected void handleOnConfigurationChanged(Configuration newConfig) {}

    /**
     * Handle onStart
     */
    protected void handleOnStart() {}

    /**
     * Handle onRestart
     */
    protected void handleOnRestart() {}

    /**
     * Handle onResume
     */
    protected void handleOnResume() {}

    /**
     * Handle onPause
     */
    protected void handleOnPause() {}

    /**
     * Handle onStop
     */
    protected void handleOnStop() {}

    /**
     * Handle onDestroy
     */
    protected void handleOnDestroy() {}

    /**
     * Give the plugins a chance to take control when a URL is about to be loaded in the WebView.
     * Returning true causes the WebView to abort loading the URL.
     * Returning false causes the WebView to continue loading the URL.
     * Returning null will defer to the default Capacitor policy
     */
    @SuppressWarnings("unused")
    public Boolean shouldOverrideLoad(Uri url) {
        return null;
    }

    /**
     * Start a new Activity.
     *
     * Note: This method must be used by all plugins instead of calling
     * {@link Activity#startActivityForResult} as it associates the plugin with
     * any resulting data from the new Activity even if this app
     * is destroyed by the OS (to free up memory, for example).
     * @param intent
     * @param resultCode
     */
    protected void startActivityForResult(PluginCall call, Intent intent, int resultCode) {
        bridge.startActivityForPluginWithResult(call, intent, resultCode);
    }

    /**
     * Execute the given runnable on the Bridge's task handler
     * @param runnable
     */
    public void execute(Runnable runnable) {
        bridge.execute(runnable);
    }

    /**
     * Shortcut for getting the plugin log tag
     * @param subTags
     */
    protected String getLogTag(String... subTags) {
        return Logger.tags(subTags);
    }

    /**
     * Gets a plugin log tag with the child's class name as subTag.
     */
    protected String getLogTag() {
        return Logger.tags(this.getClass().getSimpleName());
    }
}
