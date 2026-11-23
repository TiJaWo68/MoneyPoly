package de.in.mp.energy.sync;

/**
 * Supported automated device/cloud data sources for reading energy meters.
 */
public enum EnergyDataSourceProvider {

    SHELLY_PRO_3EM(false, false, false),
    FUSIONSOLAR_KIOSK(false, true, false),
    KATHREIN_WALLBOX(true, true, true);

    private final boolean supportsUsername;
    private final boolean usesSecret;
    private final boolean supportsSelfSigned;

    EnergyDataSourceProvider(boolean supportsUsername, boolean usesSecret, boolean supportsSelfSigned) {
        this.supportsUsername = supportsUsername;
        this.usesSecret = usesSecret;
        this.supportsSelfSigned = supportsSelfSigned;
    }

    public boolean supportsUsername() {
        return supportsUsername;
    }

    public boolean usesSecret() {
        return usesSecret;
    }

    public boolean supportsSelfSigned() {
        return supportsSelfSigned;
    }
}
