package org.onetwoone.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.onetwoone.gateway.audio.AudioBridgeManager;
import org.onetwoone.gateway.call.CallManager;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.power.PowerController;
import org.onetwoone.gateway.sip.ReconnectionStrategy;
import org.onetwoone.gateway.sip.ServiceWatchdog;
import org.onetwoone.gateway.sip.SipAccountManager;
import org.onetwoone.gateway.sip.SipEndpointManager;
import org.pjsip.pjsua2.*;

/**
 * GSM-SIP Gateway Service (Refactored v2).
 *
 * This is a facade that coordinates between specialized managers:
 * - SipEndpointManager: PJSIP endpoint lifecycle
 * - SipAccountManager: SIP registration
 * - CallManager: Call coordination
 * - AudioBridgeManager: Audio bridging
 * - PowerController: WakeLock management
 * - ReconnectionStrategy: Auto-reconnect
 * - ServiceWatchdog: Orphaned call detection
 *
 * Total: ~400 lines vs original 2000 lines
 */
public class PjsipSipService extends Service implements SipCallService {
    private static final String TAG = "GatewaySvc";
    private static final String CHANNEL_ID = "gateway_channel";
    private static final int NOTIFICATION_ID = 1;

    private static PjsipSipService instance;

    // Managers
    private GatewayConfig config;
    private SipEndpointManager endpointManager;
    private SipAccountManager accountManager;
    private CallManager callManager;
    private AudioBridgeManager audioBridge;
    private PowerController powerController;
    private ReconnectionStrategy reconnection;
    private ServiceWatchdog watchdog;
    private SmsHandler smsHandler;
    private WebConfigServer webServer;

    // Telephony
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private int lastPhoneState = TelephonyManager.CALL_STATE_IDLE;

    // State
    private boolean isRunning = false;
    private volatile boolean stopRequested = false;
    private Handler mainHandler;

    // Binder
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public PjsipSipService getService() {
            return PjsipSipService.this;
        }
    }

    static {
        try {
            System.loadLibrary("pjsua2");
            Log.d(TAG, "PJSIP library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load PJSIP: " + e.getMessage());
        }
    }

    public static PjsipSipService getInstance() {
        return instance;
    }

    // ========== Service Lifecycle ==========

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        stopRequested = false;  // Reset flag on new service instance
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize config
        GatewayConfig.init(this);
        config = GatewayConfig.getInstance();

        // Initialize managers
        initializeManagers();

        // Setup telephony listener
        setupPhoneStateListener();

        Log.d(TAG, "Service created");
    }

    private void initializeManagers() {
        // Power controller (acquire wake lock immediately)
        powerController = new PowerController(this);
        powerController.acquireCpuWakeLock();
        powerController.disableBatteryOptimizationsAsync();

        // SIP components
        endpointManager = new SipEndpointManager(config);
        accountManager = new SipAccountManager(config, endpointManager);

        // Call management
        callManager = new CallManager(this, config);
        callManager.setListener(callListener);

        // Audio bridge
        audioBridge = new AudioBridgeManager(this, config);

        // Reconnection strategy
        reconnection = new ReconnectionStrategy(this::attemptReconnect);

        // Watchdog
        watchdog = new ServiceWatchdog(this::checkOrphanedCalls);

        // Account listener
        accountManager.setListener(accountListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service starting");

        startForegroundNotification();

        if (!isRunning) {
            isRunning = true;
            reconnection.setEnabled(true);
            watchdog.start();

            // Initialize PJSIP on background thread
            new Thread(this::initializeSip, "SipInit").start();

            // Initialize SMS handler
            initSmsHandler();

            // Start web server if enabled
            if (config.isWebInterfaceEnabled()) {
                startWebServer();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroying");

        isRunning = false;
        instance = null;

        // Stop components
        watchdog.stop();
        reconnection.setEnabled(false);
        reconnection.cancel();

        if (smsHandler != null) {
            smsHandler.stop();
        }

        stopWebServer();

        // Shutdown SIP
        shutdownSip();

        // Release power
        powerController.release();

        // Cleanup telephony
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }

        stopForeground(true);
        Log.d(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ========== SIP Initialization ==========

    private void initializeSip() {
        try {
            Log.d(TAG, "Initializing SIP...");

            // Create endpoint
            endpointManager.createEndpoint();

            // Register THIS thread (SipInit) immediately after endpoint creation
            // This MUST happen before any other PJSIP calls from this thread
            if (!endpointManager.registerThread("SipInit")) {
                throw new Exception("Failed to register SipInit thread");
            }

            // Register main thread for callbacks
            mainHandler.post(() -> {
                if (!endpointManager.registerThread("MainThread")) {
                    Log.e(TAG, "Failed to register MainThread");
                }
            });

            // Initialize audio bridge
            audioBridge.initialize();

            // Create and register account
            accountManager.createAccount(this);

            Log.d(TAG, "SIP initialized");

        } catch (SipEndpointManager.TlsChangedException e) {
            // TLS setting changed - PJSIP cannot safely recreate endpoint
            // Must kill the entire process and restart
            Log.e(TAG, "TLS changed, restarting process: " + e.getMessage());
            restartProcess();
        } catch (Exception e) {
            Log.e(TAG, "SIP init failed: " + e.getMessage(), e);
            updateNotification("Error: " + e.getMessage());
            reconnection.scheduleReconnect();
        }
    }

    private void shutdownSip() {
        Log.d(TAG, "Shutting down SIP...");

        // Stop audio bridge and streams, but DON'T release (keep port alive)
        // Releasing while PJSIP still running causes NullPointerException in onFrameReceived
        audioBridge.stopBridge();
        audioBridge.stopAudioStreams();

        // Delete account
        accountManager.deleteAccount();

        // Keep endpoint alive for reuse (don't destroy it)
        // PJSIP native library crashes if we destroy and recreate endpoint in same process
        endpointManager.shutdown();

        Log.d(TAG, "SIP shutdown complete");
    }

    private void attemptReconnect() {
        if (!isRunning) return;

        Log.d(TAG, "Attempting reconnect...");

        try {
            // Check if endpoint is properly initialized (has transport)
            // CRITICAL: Must check hasTransport() - creating account without transport causes PJSIP crash
            if (!endpointManager.isInitialized() || !endpointManager.hasTransport() || accountManager.getAccount() == null) {
                // Endpoint not ready, transport missing, or account missing - need full init
                Log.d(TAG, "Endpoint/transport/account not ready, performing full initialization");
                initializeSip();
            } else {
                // Endpoint and transport ready, just re-register
                accountManager.getAccount().setRegistration(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Reconnect failed: " + e.getMessage());
            reconnection.scheduleReconnect();
        }
    }

    // ========== Account Callbacks ==========

    private final SipAccountManager.AccountListener accountListener = new SipAccountManager.AccountListener() {
        @Override
        public void onRegistrationState(boolean registered, String reason) {
            mainHandler.post(() -> {
                if (registered) {
                    Log.i(TAG, "SIP registered");
                    updateNotification("Registered");
                    reconnection.onSuccess();

                    // Process any pending SMS (may have been queued before registration)
                    if (smsHandler != null) {
                        Log.d(TAG, "Triggering SMS inbox check after registration");
                        smsHandler.processInbox();
                    }
                } else {
                    Log.w(TAG, "SIP registration failed: " + reason);
                    updateNotification("Error: " + reason);
                    reconnection.scheduleReconnect();
                }
            });
        }

        @Override
        public void onIncomingCall(GatewayAccount account, int callId, int requestedSimSlot) {
            try {
                GatewayCall call = new GatewayCall(PjsipSipService.this, account, callId);
                call.setRequestedGsmSimSlot(requestedSimSlot);
                mainHandler.post(() -> handleIncomingSipCall(call));
            } catch (Exception e) {
                Log.e(TAG, "Error creating call: " + e.getMessage());
            }
        }

        @Override
        public void onInstantMessage(String from, String to, String body, int simSlot) {
            mainHandler.post(() -> handleIncomingSipMessage(from, to, body, simSlot));
        }
    };

    // ========== Call Handling ==========

    private void handleIncomingSipCall(GatewayCall call) {
        Log.d(TAG, "Incoming SIP call");
        powerController.wakeScreen();
        callManager.onIncomingSipCall(call);
    }

    private final CallManager.CallListener callListener = new CallManager.CallListener() {
        @Override
        public void onCallStateChanged(CallManager.CallState state) {
            updateNotification("Call: " + state.name());
        }

        @Override
        public void onSipCallConnected(GatewayCall call) {
            // Start audio bridge when SIP call media is ready
            audioBridge.startBridge(call);

            // In SIP_FIRST mode, answer GSM call now that SIP is connected
            GatewayInCallService inCallService = GatewayInCallService.getInstance();
            if (inCallService != null && inCallService.getCurrentCall() != null) {
                int gsmState = inCallService.getCurrentCall().getState();
                if (gsmState == android.telecom.Call.STATE_RINGING) {
                    Log.d(TAG, "SIP connected, answering GSM call (SIP_FIRST mode)");
                    inCallService.answerCall();
                }
            }
        }

        @Override
        public void onGsmCallNeeded(String destination, int simSlot) {
            callManager.placeGsmCall(destination, simSlot);
        }

        @Override
        public void onSipCallNeeded(String destination, String callerId, int simSlot) {
            makeSipCallWithCallerId(destination, callerId, simSlot);
        }

        @Override
        public void onCallsTerminated() {
            audioBridge.stopBridge();
            audioBridge.stopAudioStreams();
            updateNotification(accountManager.isRegistered() ? "Registered" : "Not registered");
        }

        @Override
        public void onError(String error) {
            Log.e(TAG, "Call error: " + error);
            updateNotification("Error: " + error);
        }
    };

    // Callback from GatewayCall (SipCallService interface)
    @Override
    public void onCallState(GatewayCall call, int state) {
        callManager.onSipCallState(call, state);
    }

    // Callback from GatewayCall (SipCallService interface)
    @Override
    public void onCallMediaState(GatewayCall call) {
        try {
            CallInfo info = call.getInfo();
            if (info.getState() == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
                audioBridge.startBridge(call);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling media state: " + e.getMessage());
        }
    }

    // ========== GSM Call Handling ==========

    @SuppressWarnings("deprecation")
    private void setupPhoneStateListener() {
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                handlePhoneState(state, phoneNumber);
            }
        };

        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    private void handlePhoneState(int state, String phoneNumber) {
        Log.d(TAG, "Phone state: " + state);

        if (state == TelephonyManager.CALL_STATE_OFFHOOK && lastPhoneState != TelephonyManager.CALL_STATE_OFFHOOK) {
            // GSM call active
            audioBridge.startAudioStreams();
            callManager.onGsmCallConnected();
        }

        if (state == TelephonyManager.CALL_STATE_IDLE && lastPhoneState != TelephonyManager.CALL_STATE_IDLE) {
            // GSM call ended
            callManager.onGsmCallEnded();
        }

        lastPhoneState = state;
    }

    public void onIncomingGsmCall(String callerNumber, int simSlot) {
        powerController.wakeScreen();
        callManager.onIncomingGsmCall(callerNumber, simSlot);
    }

    public void onGsmCallStateChanged(android.telecom.Call call, int state) {
        if (state == android.telecom.Call.STATE_ACTIVE) {
            // Cancel incoming timeout - call is now bridged
            GatewayInCallService inCallService = GatewayInCallService.getInstance();
            if (inCallService != null) {
                inCallService.cancelIncomingTimeout();
            }

            // Start audio immediately (don't wait for mute)
            audioBridge.startAudioStreams();
            callManager.onGsmCallConnected();

            // Mute device speaker/mic in background (takes ~6 seconds)
            // This prevents local sounds but we don't block audio start
            new Thread(() -> {
                DeviceMuteManager.getInstance(this).muteAll();
            }, "MuteControls").start();
        } else if (state == android.telecom.Call.STATE_DISCONNECTED) {
            callManager.onGsmCallEnded();
            // Restore device speaker/mic
            DeviceMuteManager.getInstance(this).unmuteAll();
        }
    }

    // ========== SMS Handling ==========

    private void initSmsHandler() {
        smsHandler = new SmsHandler(this, new SmsHandler.SmsCallback() {
            @Override
            public void onIncomingSms(String from, String body, long smsId, int simSlot) {
                handleIncomingGsmSms(from, body, smsId, simSlot);
            }

            @Override
            public void onSmsSendStatus(String destination, String status, String errorMessage) {
                Log.d(TAG, "SMS to " + destination + ": " + status);
            }
        });
        smsHandler.start();
    }

    private void handleIncomingGsmSms(String from, String body, long smsId, int simSlot) {
        Log.d(TAG, "handleIncomingGsmSms: smsId=" + smsId + " from=" + from + " SIM" + simSlot + " registered=" + accountManager.isRegistered());

        if (!accountManager.isRegistered()) {
            Log.w(TAG, "Not registered, cannot forward SMS smsId=" + smsId + " - will retry after registration");
            // Remove from processed list so it can be retried after registration
            smsHandler.unprocessSms(smsId);
            return;
        }

        String destination = config.getDestinationForSim(simSlot);
        if (destination.isEmpty()) {
            Log.w(TAG, "No destination for SIM" + simSlot + ", marking smsId=" + smsId + " as read");
            smsHandler.markAsRead(smsId);
            return;
        }

        Log.d(TAG, "handleIncomingGsmSms: Forwarding smsId=" + smsId + " to SIP destination=" + destination);
        // Send as SIP MESSAGE
        sendSipMessage(destination, from, body, smsId, simSlot);
    }

    private void sendSipMessage(String toExt, String gsmSender, String body, long smsId, int simSlot) {
        Log.d(TAG, "sendSipMessage START: smsId=" + smsId + " to=" + toExt + " from=" + gsmSender);
        Buddy buddy = null;
        try {
            GatewayAccount account = accountManager.getAccount();
            if (account == null) {
                Log.e(TAG, "sendSipMessage: No account, cannot send");
                return;
            }

            String server = config.getSipServer();
            int port = config.getSipPort();
            boolean useTls = config.isUseTls();

            // Build URI with correct transport (use sip: with transport=tls, not sips:)
            String toUri = "sip:" + toExt + "@" + server + (useTls ? ";transport=tls" : "");

            // Create temporary Buddy to send MESSAGE
            BuddyConfig buddyConfig = new BuddyConfig();
            buddyConfig.setUri(toUri);

            buddy = new Buddy();
            buddy.create(account, buddyConfig);

            SendInstantMessageParam prm = new SendInstantMessageParam();
            prm.setContent(body);
            prm.setContentType("text/plain");

            // Add X-GSM-CallerID header (like calls) - don't override From URI
            SipTxOption txOpt = prm.getTxOption();
            SipHeaderVector headers = txOpt.getHeaders();

            SipHeader callerHeader = new SipHeader();
            callerHeader.setHName("X-GSM-CallerID");
            callerHeader.setHValue(gsmSender);
            headers.add(callerHeader);

            Log.d(TAG, "sendSipMessage: Calling buddy.sendInstantMessage for smsId=" + smsId);
            buddy.sendInstantMessage(prm);

            Log.i(TAG, "SIP MESSAGE sent to " + toUri + " from " + gsmSender + " (SMS id=" + smsId + ") - now marking as read");
            smsHandler.markAsRead(smsId);
            Log.d(TAG, "sendSipMessage SUCCESS: smsId=" + smsId + " marked as read");

        } catch (Exception e) {
            Log.e(TAG, "sendSipMessage FAILED for smsId=" + smsId + ": " + e.getMessage(), e);
            smsHandler.unprocessSms(smsId);
        } finally {
            // Clean up buddy
            if (buddy != null) {
                try {
                    buddy.delete();
                } catch (Exception ignored) {}
            }
        }
    }

    private void handleIncomingSipMessage(String from, String to, String body, int simSlot) {
        Log.d(TAG, "handleIncomingSipMessage: from=" + from + " to=" + to + " body=\"" + body + "\" SIM" + simSlot);

        // Extract phone number from 'to' URI
        String phoneNumber = extractPhoneNumber(to);
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            Log.w(TAG, "Invalid destination in SIP MESSAGE - to=\"" + to + "\" not a phone number, IGNORING");
            return;
        }

        Log.d(TAG, "handleIncomingSipMessage: Sending GSM SMS to " + phoneNumber);
        // Send via GSM
        if (smsHandler != null) {
            smsHandler.sendSms(phoneNumber, body, simSlot);
        }
    }

    private String extractPhoneNumber(String uri) {
        if (uri == null) return null;
        String cleaned = uri.replaceAll("[<>]", "");
        if (cleaned.startsWith("sips:")) cleaned = cleaned.substring(5);
        else if (cleaned.startsWith("sip:")) cleaned = cleaned.substring(4);
        int at = cleaned.indexOf('@');
        if (at > 0) cleaned = cleaned.substring(0, at);
        if (cleaned.matches("^\\+?[0-9]{10,15}$")) return cleaned;
        return null;
    }

    // ========== Watchdog ==========

    private void checkOrphanedCalls() {
        if (!callManager.hasActiveCall()) return;
        if (callManager.isInGracePeriod()) return;

        // Check if GSM call exists
        if (lastPhoneState == TelephonyManager.CALL_STATE_IDLE) {
            GatewayCall sipCall = callManager.getCurrentSipCall();
            if (sipCall != null) {
                Log.w(TAG, "Orphaned SIP call detected, terminating");
                callManager.terminateAllCalls();
            }
        }
    }

    // ========== Public API ==========

    public void makeSipCallWithCallerId(String destination, String callerId, int simSlot) {
        try {
            GatewayAccount account = accountManager.getAccount();
            if (account == null) {
                Log.e(TAG, "No SIP account");
                return;
            }

            String server = config.getSipServer();
            boolean useTls = config.isUseTls();

            // Build SIP URI (with TLS transport if enabled)
            String uri = "sip:" + destination + "@" + server + (useTls ? ";transport=tls" : "");

            GatewayCall call = new GatewayCall(this, account);

            CallOpParam prm = new CallOpParam(true);  // true = use default values

            // Add custom SIP headers (Asterisk reads via PJSIP_HEADER())
            SipTxOption txOpt = prm.getTxOption();
            SipHeaderVector headers = new SipHeaderVector();

            // Add CallerID header
            if (callerId != null && !callerId.isEmpty()) {
                SipHeader callerIdHeader = new SipHeader();
                callerIdHeader.setHName("X-GSM-CallerID");
                callerIdHeader.setHValue(callerId);
                headers.add(callerIdHeader);
                Log.d(TAG, "Added X-GSM-CallerID: " + callerId);
            }

            txOpt.setHeaders(headers);

            call.makeCall(uri, prm);

            // Store call in CallManager for tracking and cleanup
            callManager.setOutgoingSipCall(call);

            Log.d(TAG, "SIP call to " + uri + " (CallerID: " + callerId + ", SIM: " + simSlot + ")");

        } catch (Exception e) {
            Log.e(TAG, "Failed to make SIP call: " + e.getMessage());
        }
    }

    public synchronized void hangupCall() {
        callManager.terminateAllCalls();
    }

    public void stop() {
        if (stopRequested) {
            Log.w(TAG, "Stop already requested, ignoring duplicate");
            return;
        }
        stopRequested = true;
        Log.d(TAG, "Stop requested");
        reconnection.setEnabled(false);
        stopSelf();
    }

    /**
     * Reload configuration and re-register SIP account.
     * Use this instead of full service restart when only config changed.
     * Thread-safe, can be called from any thread.
     */
    public void reloadConfig() {
        mainHandler.post(this::doReloadConfig);
    }

    private volatile boolean reloadInProgress = false;

    private void doReloadConfig() {
        if (reloadInProgress) {
            Log.w(TAG, "Reload already in progress");
            return;
        }
        reloadInProgress = true;

        Log.i(TAG, "Reloading configuration...");
        updateNotification("Reloading...");

        new Thread(() -> {
            try {
                // 0. Check if endpoint exists
                if (!endpointManager.isInitialized()) {
                    Log.w(TAG, "Endpoint not initialized, cannot reload - restarting service");
                    mainHandler.post(() -> {
                        stop();
                        // Service will be restarted by system due to START_STICKY
                    });
                    return;
                }

                // 1. Register this thread with PJSIP (required for all threads calling PJSIP)
                if (!endpointManager.registerThread("ConfigReload")) {
                    Log.e(TAG, "Failed to register thread, aborting reload");
                    mainHandler.post(() -> updateNotification("Reload failed: thread registration"));
                    return;
                }

                // 2. Stop any active calls (on main thread, but don't wait)
                mainHandler.post(() -> callManager.terminateAllCalls());
                Thread.sleep(100);

                // 3. Stop audio streams (but keep port alive)
                audioBridge.stopBridge();
                audioBridge.stopAudioStreams();

                // 4. Delete old account
                accountManager.deleteAccount();

                // 5. Small delay for cleanup
                Thread.sleep(500);

                // 6. Check if endpoint needs recreation (TLS changed)
                if (endpointManager.needsRecreation()) {
                    // TLS change requires killing the entire process because:
                    // 1. PJSIP endpoint cannot be safely destroyed/recreated at runtime
                    // 2. Thread registration is tied to specific Endpoint instance
                    // 3. Static endpoint survives service restart but threads don't
                    Log.i(TAG, "TLS setting changed, restarting process");
                    restartProcess();
                    return;
                }

                // 7. Create new account with new settings
                accountManager.createAccount(PjsipSipService.this);

                Log.i(TAG, "Configuration reloaded successfully");
                mainHandler.post(() -> updateNotification("SIP Registered"));

                // 8. Restart MainActivity to refresh UI
                mainHandler.post(() -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(PjsipSipService.this, MainActivity.class);
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                                       android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to restart activity: " + e.getMessage());
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Reload failed: " + e.getMessage(), e);
                mainHandler.post(() -> updateNotification("Reload error: " + e.getMessage()));
            } finally {
                reloadInProgress = false;
            }
        }, "ConfigReload").start();
    }

    public void setSipConfig(String server, int port, String user, String password) {
        config.updateSipConfig(server, port, user, password, config.getSipRealm(), config.isUseTls());
    }

    public void setSimDestinations(String sim1, String sim2) {
        config.updateSimDestinations(sim1, sim2);
    }

    // ========== Process Restart ==========

    /**
     * Restart the entire process by killing it and launching MainActivity via root.
     * This is needed when TLS setting changes because PJSIP endpoint cannot be safely
     * destroyed/recreated at runtime.
     */
    private void restartProcess() {
        new Thread(() -> {
            try {
                Log.i(TAG, "Restarting process via root...");

                // Launch MainActivity via root (bypasses background activity restrictions)
                // Flags: -S = force stop before start, -W = wait for launch to complete
                RootHelper.execRoot("am start -S -W -n org.onetwoone.gateway/.MainActivity");

                // Small delay to let activity start
                Thread.sleep(500);

                // Kill this process
                Log.i(TAG, "Killing process for restart");
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);

            } catch (Exception e) {
                Log.e(TAG, "Failed to restart: " + e.getMessage());
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }
        }, "ProcessRestart").start();
    }

    // ========== Web Server ==========

    public void startWebServer() {
        if (webServer != null) return;
        try {
            webServer = new WebConfigServer(this, GatewayConfig.WEB_SERVER_PORT);
            webServer.start();
            Log.i(TAG, "Web server started on port " + GatewayConfig.WEB_SERVER_PORT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start web server: " + e.getMessage());
        }
    }

    public void stopWebServer() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
    }

    public boolean isWebServerRunning() {
        return webServer != null;
    }

    // ========== Status ==========

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("SIP: ").append(accountManager.getStatusString()).append("\n");
        sb.append("Call: ").append(callManager.getStatusString()).append("\n");
        sb.append("Audio: ").append(audioBridge.getStatusString());
        return sb.toString();
    }

    public boolean isRunning() {
        return isRunning;
    }

    public boolean isSipRegistered() {
        return accountManager.isRegistered();
    }

    // ========== Notifications ==========

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Gateway Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."));
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        return builder
            .setContentTitle("GSM-SIP Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
