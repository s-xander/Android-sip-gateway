package org.onetwoone.gateway;

import android.util.Log;

import org.pjsip.pjsua2.*;

/**
 * PJSIP Call implementation for GSM-SIP Gateway.
 * Handles call state changes and media state.
 */
public class GatewayCall extends Call {
    private static final String TAG = "GatewayCall";

    private SipCallService service;
    private volatile boolean disposed = false;
    // Explicit SIM override parsed from the SIP INVITE (0 = use legacy routing).
    private int requestedGsmSimSlot = 0;

    public void setRequestedGsmSimSlot(int slot) {
        requestedGsmSimSlot = (slot == 1 || slot == 2) ? slot : 0;
    }

    public int getRequestedGsmSimSlot() {
        return requestedGsmSimSlot;
    }

    /**
     * Constructor for outgoing calls
     */
    public GatewayCall(SipCallService service, Account account) {
        super(account);
        this.service = service;
    }

    /**
     * Constructor for incoming calls
     */
    public GatewayCall(SipCallService service, Account account, int callId) {
        super(account, callId);
        this.service = service;
    }

    /**
     * Mark this call as disposed - no more callbacks will be processed.
     * Call this when the call is disconnected.
     */
    public void dispose() {
        disposed = true;
        service = null;
        Log.d(TAG, "Call disposed");
    }

    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
        if (disposed) {
            Log.d(TAG, "Ignoring onCallState - call disposed");
            return;
        }

        try {
            CallInfo info = getInfo();
            int state = info.getState();
            String stateText = info.getStateText();

            Log.d(TAG, "Call state: " + stateText + " (" + state + ")");

            // Mark as disposed on disconnect to prevent further callbacks
            if (state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                disposed = true;
            }

            if (service != null) {
                service.onCallState(this, state);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onCallState: " + e.getMessage());
        }
    }

    @Override
    public void onCallMediaState(OnCallMediaStateParam prm) {
        if (disposed) {
            Log.d(TAG, "Ignoring onCallMediaState - call disposed");
            return;
        }

        Log.d(TAG, "Media state changed");

        try {
            if (service != null) {
                service.onCallMediaState(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onCallMediaState: " + e.getMessage());
        }
    }

    @Override
    public void onCallTransferRequest(OnCallTransferRequestParam prm) {
        Log.d(TAG, "Transfer request: " + prm.getDstUri());
        // Reject transfers for now
        prm.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
    }

    @Override
    public void onCallReplaced(OnCallReplacedParam prm) {
        Log.d(TAG, "Call replaced by " + prm.getNewCallId());
    }

    @Override
    public void onInstantMessage(OnInstantMessageParam prm) {
        Log.d(TAG, "IM: " + prm.getMsgBody());
    }

    @Override
    public void onDtmfDigit(OnDtmfDigitParam prm) {
        Log.d(TAG, "DTMF: " + prm.getDigit());
    }
}
