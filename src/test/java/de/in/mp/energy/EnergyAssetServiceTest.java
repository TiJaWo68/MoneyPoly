package de.in.mp.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnergyAssetServiceTest {

    private EnergyAssetRepository repository;
    private EnergyAssetService service;

    @BeforeEach
    void setUp() {
        repository = mock(EnergyAssetRepository.class);
        service = new EnergyAssetService(repository);
    }

    @Test
    void saveRejectsBlankName() {
        EnergyAsset asset = asset("   ", "8500.00", "0.00");

        assertThatThrownBy(() -> service.save(asset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
    }

    @Test
    void saveRejectsNonPositiveInvestmentCost() {
        EnergyAsset asset = asset("PV Dach", "0.00", "0.00");

        assertThatThrownBy(() -> service.save(asset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Investment cost");
    }

    @Test
    void saveRejectsNegativeInitialAmortized() {
        EnergyAsset asset = asset("PV Dach", "8500.00", "-10.00");

        assertThatThrownBy(() -> service.save(asset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Initial amortized");
    }

    @Test
    void saveRejectsMissingInstallationDate() {
        EnergyAsset asset = asset("PV Dach", "8500.00", "100.00");
        asset.setInstallationDate(null);

        assertThatThrownBy(() -> service.save(asset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Installation date");
    }

    @Test
    void savePersistsValidAssetAndTrimsName() {
        EnergyAsset asset = asset("  PV Dach  ", "8500.00", "1200.00");
        when(repository.save(any(EnergyAsset.class))).thenAnswer(inv -> inv.getArgument(0));

        EnergyAsset saved = service.save(asset);

        assertThat(saved.getName()).isEqualTo("PV Dach");
        assertThat(saved.getInvestmentCost()).isEqualByComparingTo("8500.00");
        assertThat(saved.getInitialAmortized()).isEqualByComparingTo("1200.00");
    }

    @Test
    void deleteRemovesExistingAsset() {
        UUID id = UUID.randomUUID();
        EnergyAsset asset = asset("Wallbox", "1500.00", "0.00");
        asset.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(asset));

        service.delete(id);

        verify(repository).delete(asset);
    }

    @Test
    void findAllDelegatesToRepository() {
        EnergyAsset asset = asset("PV Dach", "8500.00", "0.00");
        when(repository.findAllByOrderByInstallationDateAscNameAsc()).thenReturn(List.of(asset));

        assertThat(service.findAll()).containsExactly(asset);
    }

    private EnergyAsset asset(String name, String cost, String initialAmortized) {
        EnergyAsset asset = new EnergyAsset();
        asset.setName(name);
        asset.setInstallationDate(LocalDate.of(2024, 7, 1));
        asset.setInvestmentCost(new BigDecimal(cost));
        asset.setInitialAmortized(new BigDecimal(initialAmortized));
        return asset;
    }
}

