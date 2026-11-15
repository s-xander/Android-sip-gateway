package org.onetwoone.gateway.sip;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the optional X-GSM-SIM SIP header used to choose the SIM for one call. */
public final class SimSelectionHeader {
    private static final Pattern HEADER_PATTERN =
        Pattern.compile("(?im)^X-GSM-SIM[ \\t]*:[ \\t]*([12])[ \\t]*\\r?$");

    private SimSelectionHeader() {
        // Utility class.
    }

    /**
     * @param rawSipMessage complete SIP request text
     * @return 1 or 2 when explicitly selected; 0 when absent or invalid
     */
    public static int parse(String rawSipMessage) {
        if (rawSipMessage == null || rawSipMessage.isEmpty()) return 0;
        Matcher matcher = HEADER_PATTERN.matcher(rawSipMessage);
        if (!matcher.find()) return 0;
        return Integer.parseInt(matcher.group(1));
    }
}
