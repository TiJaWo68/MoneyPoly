package de.in.mp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import de.in.mp.energy.EnergyAssetRepository;
import de.in.mp.energy.EnergyHouse;
import de.in.mp.energy.EnergyHouseRepository;
import de.in.mp.energy.EnergyHouseService;
import de.in.mp.energy.EnergyTariff;
import de.in.mp.energy.EnergyTariffRepository;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReadingPhotoService;
import de.in.mp.energy.MeterReadingRepository;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterRepository;
import de.in.mp.finance.Account;
import de.in.mp.finance.AccountRepository;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.CategoryRepository;
import de.in.mp.finance.Household;
import de.in.mp.finance.HouseholdRepository;
import de.in.mp.finance.Person;
import de.in.mp.finance.PersonRepository;
import de.in.mp.finance.PortfolioPosition;
import de.in.mp.finance.PortfolioPositionRepository;
import de.in.mp.finance.SnapshotValue;
import de.in.mp.finance.WealthEvent;
import de.in.mp.finance.WealthEventRepository;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractRepository;
import de.in.mp.loans.LoanRepaymentRepository;
import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;

class DemoSeedServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void seedsAllAccountTypesTenYearHistoryPortfolioAndChronologicalWealthEvents() {
        AppUserRepository userRepo = mock(AppUserRepository.class);
        HouseholdRepository householdRepo = mock(HouseholdRepository.class);
        EnergyHouseRepository houseRepo = mock(EnergyHouseRepository.class);
        EnergyHouseService houseService = mock(EnergyHouseService.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        AccountSnapshotService snapshotService = mock(AccountSnapshotService.class);
        CategoryRepository categoryRepo = mock(CategoryRepository.class);
        PersonRepository personRepo = mock(PersonRepository.class);
        LoanContractRepository loanRepo = mock(LoanContractRepository.class);
        LoanRepaymentRepository repaymentRepo = mock(LoanRepaymentRepository.class);
        PortfolioPositionRepository positionRepo = mock(PortfolioPositionRepository.class);
        WealthEventRepository wealthEventRepo = mock(WealthEventRepository.class);
        EnergyAssetRepository assetRepo = mock(EnergyAssetRepository.class);
        MeterRepository meterRepo = mock(MeterRepository.class);
        EnergyTariffRepository tariffRepo = mock(EnergyTariffRepository.class);
        MeterReadingRepository readingRepo = mock(MeterReadingRepository.class);
        MeterReadingService readingService = mock(MeterReadingService.class);
        MeterReadingPhotoService photoService = mock(MeterReadingPhotoService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        when(householdRepo.findByNameIgnoreCase(DemoSeedService.DEMO_HOUSEHOLD_NAME)).thenReturn(Optional.empty());
        when(householdRepo.save(any(Household.class))).thenAnswer(inv -> {
            Household h = inv.getArgument(0);
            h.setId(UUID.randomUUID());
            return h;
        });
        when(houseRepo.findByNameIgnoreCase(DemoSeedService.DEMO_HOUSE_NAME)).thenReturn(Optional.empty());
        when(houseRepo.save(any(EnergyHouse.class))).thenAnswer(inv -> {
            EnergyHouse h = inv.getArgument(0);
            h.setId(UUID.randomUUID());
            return h;
        });
        when(userRepo.findByUsername(DemoSeedService.DEMO_USERNAME)).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(houseService.findAssignmentsMapForUser(any())).thenReturn(Map.of());

        List<Account> savedAccounts = new ArrayList<>();
        when(accountRepo.findByHouseholdIdOrderByAccountTypeAscNameAsc(any())).thenReturn(List.of());
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            savedAccounts.add(a);
            return a;
        });
        when(personRepo.findByHouseholdIdOrderByNameAsc(any())).thenReturn(List.of());
        when(personRepo.save(any(Person.class))).thenAnswer(inv -> {
            Person p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(loanRepo.findByHouseholdIdOrderByDesignationAsc(any())).thenReturn(List.of());
        when(loanRepo.save(any(LoanContract.class))).thenAnswer(inv -> {
            LoanContract c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
        when(repaymentRepo.findByLoanContractIdOrderByRepaymentDateAsc(any())).thenReturn(List.of());
        when(positionRepo.findByAccountIdOrderByNameAsc(any())).thenReturn(List.of());
        when(wealthEventRepo.findByHouseholdIdOrderByEventDateAscCreatedAtAsc(any())).thenReturn(List.of());
        when(assetRepo.findByHouseIdOrderByInstallationDateAscNameAsc(any())).thenReturn(List.of());
        when(meterRepo.findByHouseIdOrderByDisplayOrderAscNameAsc(any())).thenReturn(List.of());
        when(meterRepo.save(any(Meter.class))).thenAnswer(inv -> {
            Meter m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        when(tariffRepo.findByHouseIdOrderByForMeterTypeAscValidFromDesc(any())).thenReturn(List.of());
        when(readingRepo.findByMeterIdOrderByReadingDateAsc(any())).thenReturn(List.of());

        DemoSeedService service = new DemoSeedService(
                userRepo, householdRepo, houseRepo, houseService, accountRepo, snapshotService,
                categoryRepo, personRepo, loanRepo, repaymentRepo, positionRepo, wealthEventRepo,
                assetRepo, meterRepo, tariffRepo, readingRepo, readingService, photoService, passwordEncoder);

        service.ensureDemoTenant();

        // All 5 account types must be created
        assertThat(savedAccounts)
                .extracting(Account::getAccountType)
                .contains(AccountType.INSTITUTION, AccountType.CHECKING, AccountType.SAVINGS,
                        AccountType.INVESTMENT, AccountType.LOAN);

        // Savings series must span 10 years monthly (121 points) and stay positive throughout
        Account savings = savedAccounts.stream()
                .filter(a -> a.getAccountType() == AccountType.SAVINGS)
                .findFirst().orElseThrow();
        ArgumentCaptor<List<SnapshotValue>> snapCaptor = ArgumentCaptor.forClass(List.class);
        verify(snapshotService).recordSnapshots(eq(savings.getId()), snapCaptor.capture());
        List<SnapshotValue> savingsSeries = snapCaptor.getValue();
        assertThat(savingsSeries).hasSize(121);
        assertThat(savingsSeries).allSatisfy(v -> assertThat(v.balance()).isPositive());

        // 4 stock positions totaling ~50k EUR
        ArgumentCaptor<Iterable<PortfolioPosition>> posCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(positionRepo).saveAll(posCaptor.capture());
        List<PortfolioPosition> positions = new ArrayList<>();
        posCaptor.getValue().forEach(positions::add);
        assertThat(positions).hasSize(4);
        BigDecimal portfolioValue = positions.stream()
                .map(p -> p.getMarketValueEur().orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(portfolioValue).isBetween(new BigDecimal("48000"), new BigDecimal("52000"));

        // 5 wealth events in strict chronology: Car1 -> House -> PV -> Loan -> EV2
        ArgumentCaptor<Iterable<WealthEvent>> eventCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(wealthEventRepo).saveAll(eventCaptor.capture());
        List<WealthEvent> events = new ArrayList<>();
        eventCaptor.getValue().forEach(events::add);
        assertThat(events).hasSize(5);
        assertThat(events.get(0).getEventDate()).isBefore(events.get(1).getEventDate());
        assertThat(events.get(1).getEventDate()).isBefore(events.get(2).getEventDate());
        assertThat(events.get(2).getEventDate()).isBefore(events.get(3).getEventDate());
        assertThat(events.get(3).getEventDate()).isBefore(events.get(4).getEventDate());
        assertThat(events.get(3).getLoanContractId()).isNotNull();

        // Both active and expired energy tariffs are seeded
        ArgumentCaptor<EnergyTariff> tariffCaptor = ArgumentCaptor.forClass(EnergyTariff.class);
        verify(tariffRepo, atLeastOnce()).save(tariffCaptor.capture());
        assertThat(tariffCaptor.getAllValues()).anyMatch(t -> t.getValidTo() != null);
    }
}
