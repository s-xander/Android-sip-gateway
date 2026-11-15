package org.onetwoone.gateway.sip;

import android.util.Log;

import org.onetwoone.gateway.GatewayAccount;
import org.onetwoone.gateway.config.GatewayConfig;
import org.pjsip.pjsua2.*;

/**
 * Manages SIP account registration.
 *
 * Responsibilities:
 * - Creating and configuring the SIP account
 * - Handling registration state changes
 * - Managing SRTP settings
 * - Credential management
 */
public class SipAccountManager {
    private static final String TAG = "SipAccount";

    private final GatewayConfig config;
    private final SipEndpointManager endpointManager;

    private GatewayAccount account;
    private boolean registered = false;
    private String lastError = null;

    public interface AccountListener {
        void onRegistrationState(boolean registered, String reason);
        void onIncomingCall(GatewayAccount account, int callId, int requestedSimSlot);
        void onInstantMessage(String from, String to, String body, int simSlot);
    }

    private AccountListener listener;

    public SipAccountManager(GatewayConfig config, SipEndpointManager endpointManager) {
        this.config = config;
        this.endpointManager = endpointManager;
    }

    public void setListener(AccountListener listener) {
        this.listener = listener;
    }

    /**
     * Get the current account.
     */
    public GatewayAccount getAccount() {
        return account;
    }

    /**
     * Check if currently registered.
     */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * Get last registration error.
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Create and register the SIP account.
     *
     * @param callbackService Service to receive callbacks (for GatewayAccount)
     * @throws Exception if registration fails
     */
    public void createAccount(Object callbackService) throws Exception {
        Endpoint endpoint = endpointManager.getEndpoint();
        if (endpoint == null) {
            throw new IllegalStateException("Endpoint not created");
        }

        // CRITICAL: Check that transport exists before creating account
        // PJSIP crashes with assertion failure if account is created without transport
        if (!endpointManager.hasTransport()) {
            throw new IllegalStateException("No transport available - cannot create account");
        }

        String server = config.getSipServer();
        String user = config.getSipUser();
        String password = config.getSipPassword();
        String realm = config.getSipRealm();
        // Empty realm should be treated as wildcard for digest auth
        if (realm == null || realm.isEmpty()) {
            realm = "*";
        }
        boolean useTls = config.isUseTls();
        int port = config.getEffectiveSipPort();

        if (server.isEmpty() || user.isEmpty()) {
            throw new IllegalArgumentException("SIP server and user must be configured");
        }

        Log.d(TAG, "Registering account: " + user + "@" + server + ":" + port + " (TLS=" + useTls + ", realm=" + realm + ")");

        // Create account config
        AccountConfig accConfig = new AccountConfig();

        // Build SIP URI
        String transport = useTls ? ";transport=tls" : "";
        String idUri = "sip:" + user + "@" + server + transport;
        String regUri = "sip:" + server + ":" + port + transport;

        accConfig.setIdUri(idUri);
        accConfig.getRegConfig().setRegistrarUri(regUri);
        accConfig.getRegConfig().setTimeoutSec(300);
        accConfig.getRegConfig().setRetryIntervalSec(60);

        // Credentials
        AuthCredInfo cred = new AuthCredInfo("digest", realm, user, 0, password);
        accConfig.getSipConfig().getAuthCreds().add(cred);

        // NAT config
        AccountNatConfig natConfig = accConfig.getNatConfig();
        natConfig.setIceEnabled(false);
        natConfig.setSdpNatRewriteUse(1);
        natConfig.setViaRewriteUse(1);
        natConfig.setSipOutboundUse(1);

        // Media config - SRTP mandatory
        AccountMediaConfig mediaConfig = accConfig.getMediaConfig();
        mediaConfig.setSrtpUse(pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY);
        mediaConfig.setSrtpSecureSignaling(0); // Don't require TLS for SRTP
        Log.d(TAG, "SRTP set to mandatory");

        // Create account with callback service
        // The callbackService should be PjsipSipService which handles callbacks
        account = new AccountCallbackWrapper(callbackService);
        account.create(accConfig);

        Log.d(TAG, "Account created, waiting for registration...");
    }

    /**
     * Unregister and delete the account.
     */
    public void deleteAccount() {
        if (account == null) {
            return;
        }

        Log.d(TAG, "Deleting account");

        try {
            account.setRegistration(false);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering: " + e.getMessage());
        }

        try {
            account.delete();
        } catch (Exception e) {
            Log.w(TAG, "Error deleting account: " + e.getMessage());
        }

        account = null;
        registered = false;
    }

    /**
     * Called by GatewayAccount when registration state changes.
     */
    public void onRegState(boolean isRegistered, String reason) {
        this.registered = isRegistered;

        if (!isRegistered) {
            this.lastError = reason;
        } else {
            this.lastError = null;
        }

        Log.d(TAG, "Registration state: " + (isRegistered ? "registered" : "failed: " + reason));

        if (listener != null) {
            listener.onRegistrationState(isRegistered, reason);
        }
    }

    /**
     * Get status string for UI.
     */
    public String getStatusString() {
        if (account == null) {
            return "Not configured";
        }
        if (registered) {
            return "Registered";
        }
        if (lastError != null) {
            return "Error: " + lastError;
        }
        return "Connecting...";
    }

    /**
     * Wrapper class that delegates callbacks to the service.
     * This avoids tight coupling between Account and Service.
     */
    private class AccountCallbackWrapper extends GatewayAccount {
        private final Object callbackService;

        AccountCallbackWrapper(Object callbackService) {
            super(); // Callbacks handled by this wrapper
            this.callbackService = callbackService;
        }

        @Override
        public void onRegState(OnRegStateParam prm) {
            try {
                AccountInfo info = getInfo();
                boolean isReg = (info.getRegStatus() == pjsip_status_code.PJSIP_SC_OK);
                String reason = info.getRegStatusText();

                SipAccountManager.this.onRegState(isReg, reason);
            } catch (Exception e) {
                Log.e(TAG, "Error in onRegState: " + e.getMessage());
            }
        }

        @Override
        public void onIncomingCall(OnIncomingCallParam prm) {
            Log.d(TAG, "Incoming call, callId=" + prm.getCallId());

            int requestedSimSlot = 0;
            try {
                SipRxData receivedData = prm.getRdata();
                if (receivedData != null) {
                    requestedSimSlot = SimSelectionHeader.parse(receivedData.getWholeMsg());
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not parse X-GSM-SIM header: " + e.getMessage());
            }

            if (listener != null) {
                listener.onIncomingCall(this, prm.getCallId(), requestedSimSlot);
            }
        }

        @Override
        public void onInstantMessage(OnInstantMessageParam prm) {
            try {
                String from = prm.getFromUri();
                String to = prm.getToUri();
                String body = prm.getMsgBody();
                String contentType = prm.getContentType();

                // Determine SIM slot from caller extension
                int simSlot = config.getSimSlotForCaller(extractExtension(from));

                Log.i(TAG, ">>> RECEIVED SIP MESSAGE: from=" + from + ", to=" + to + ", body=\"" + body + "\", contentType=" + contentType + ", SIM=" + simSlot);

                if (contentType != null && contentType.contains("text/plain")) {
                    if (listener != null) {
                        listener.onInstantMessage(from, to, body, simSlot);
                    }
                } else {
                    Log.w(TAG, "Ignoring non-text/plain MESSAGE: contentType=" + contentType);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling IM: " + e.getMessage());
            }
        }

        private String extractExtension(String uri) {
            if (uri == null) return "";
            // Extract user part from sip:user@domain or sips:user@domain
            String cleaned = uri.replaceAll("[<>]", "");
            if (cleaned.startsWith("sips:")) {
                cleaned = cleaned.substring(5);
            } else if (cleaned.startsWith("sip:")) {
                cleaned = cleaned.substring(4);
            }
            int atPos = cleaned.indexOf('@');
            if (atPos > 0) {
                return cleaned.substring(0, atPos);
            }
            return cleaned;
        }
    }
}
