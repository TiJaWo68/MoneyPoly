package de.in.mp.energy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for managing {@link EnergyAsset} entities (PV systems,
 * wallboxes, battery storage).
 */
@Service
public class EnergyAssetService {

    private static final int NAME_MAX_LENGTH = 80;

    private final EnergyAssetRepository repository;

    public EnergyAssetService(EnergyAssetRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EnergyAsset> findAll() {
        return repository.findAllByOrderByInstallationDateAscNameAsc();
    }

    @Transactional(readOnly = true)
    public EnergyAsset requireAsset(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Energy asset not found: " + id));
    }

    @Transactional
    public EnergyAsset save(EnergyAsset asset) {
        validate(asset);
        return repository.save(asset);
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(requireAsset(id));
    }

    private void validate(EnergyAsset asset) {
        if (asset == null) {
            throw new IllegalArgumentException("Energy asset must not be null");
        }
        if (asset.getName() == null || asset.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        String trimmedName = asset.getName().trim();
        if (trimmedName.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Name must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        asset.setName(trimmedName);

        if (asset.getInstallationDate() == null) {
            throw new IllegalArgumentException("Installation date is required");
        }
        if (asset.getInvestmentCost() == null || asset.getInvestmentCost().signum() <= 0) {
            throw new IllegalArgumentException("Investment cost must be greater than zero");
        }
        if (asset.getInitialAmortized() == null) {
            asset.setInitialAmortized(BigDecimal.ZERO);
        }
        if (asset.getInitialAmortized().signum() < 0) {
            throw new IllegalArgumentException("Initial amortized amount must not be negative");
        }
    }
}

