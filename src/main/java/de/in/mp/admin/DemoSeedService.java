package de.in.mp.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.energy.EnergyAsset;
import de.in.mp.energy.EnergyAssetRepository;
import de.in.mp.energy.EnergyHouse;
import de.in.mp.energy.EnergyHouseRepository;
import de.in.mp.energy.EnergyHouseService;
import de.in.mp.energy.EnergyTariff;
import de.in.mp.energy.EnergyTariffRepository;
import de.in.mp.energy.HouseAccessRole;
import de.in.mp.energy.Meter;
import de.in.mp.energy.MeterReadingService;
import de.in.mp.energy.MeterRepository;
import de.in.mp.energy.MeterType;
import de.in.mp.finance.Account;
import de.in.mp.finance.AccountRepository;
import de.in.mp.finance.AccountSnapshotService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.Category;
import de.in.mp.finance.CategoryRepository;
import de.in.mp.finance.Household;
import de.in.mp.finance.HouseholdRepository;
import de.in.mp.finance.Person;
import de.in.mp.finance.PersonRepository;
import de.in.mp.finance.PersonRole;
import de.in.mp.finance.SnapshotValue;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractRepository;
import de.in.mp.security.AppRole;
import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;
import de.in.mp.security.AuthProvider;

/**
 * Seeds a self-contained synthetic demo tenant (demo household, demo energy
 * house and restricted {@code ROLE_DEMO} login) once initial setup has
 * created at least one administrator account.
 */
@Service
public class DemoSeedService implements ApplicationRunner {

    public static final String DEMO_USERNAME = "demo";
    public static final String DEMO_HOUSEHOLD_NAME = "Demo-Familie";
    public static final String DEMO_HOUSE_NAME = "Demo-Haus";

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoSeedService.class);
    private static final String DEMO_EMAIL = "demo@moneypoly.local";

    private final AppUserRepository appUserRepository;
    private final HouseholdRepository householdRepository;
    private final EnergyHouseRepository energyHouseRepository;
    private final EnergyHouseService energyHouseService;
    private final AccountRepository accountRepository;
    private final AccountSnapshotService snapshotService;
    private final CategoryRepository categoryRepository;
    private final PersonRepository personRepository;
    private final LoanContractRepository loanContractRepository;
    private final EnergyAssetRepository energyAssetRepository;
    private final MeterRepository meterRepository;
    private final EnergyTariffRepository energyTariffRepository;
    private final MeterReadingService meterReadingService;
    private final PasswordEncoder passwordEncoder;

    public DemoSeedService(
            AppUserRepository appUserRepository,
            HouseholdRepository householdRepository,
            EnergyHouseRepository energyHouseRepository,
            EnergyHouseService energyHouseService,
            AccountRepository accountRepository,
            AccountSnapshotService snapshotService,
            CategoryRepository categoryRepository,
            PersonRepository personRepository,
            LoanContractRepository loanContractRepository,
            EnergyAssetRepository energyAssetRepository,
            MeterRepository meterRepository,
            EnergyTariffRepository energyTariffRepository,
            MeterReadingService meterReadingService,
            PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.householdRepository = householdRepository;
        this.energyHouseRepository = energyHouseRepository;
        this.energyHouseService = energyHouseService;
        this.accountRepository = accountRepository;
        this.snapshotService = snapshotService;
        this.categoryRepository = categoryRepository;
        this.personRepository = personRepository;
        this.loanContractRepository = loanContractRepository;
        this.energyAssetRepository = energyAssetRepository;
        this.meterRepository = meterRepository;
        this.energyTariffRepository = energyTariffRepository;
        this.meterReadingService = meterReadingService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (appUserRepository.count() == 0) {
            // Keep fresh database empty until initial admin setup is completed.
            return;
        }
        ensureDemoTenant();
    }

    @Transactional
    public void ensureDemoTenant() {
        if (appUserRepository.findByUsername(DEMO_USERNAME).isPresent()) {
            return;
        }

        LOGGER.info("Seeding synthetic demo household, demo house and demo account");

        Household demoHousehold = householdRepository.findByNameIgnoreCase(DEMO_HOUSEHOLD_NAME)
                .orElseGet(() -> createDemoHousehold());
        EnergyHouse demoHouse = energyHouseRepository.findByNameIgnoreCase(DEMO_HOUSE_NAME)
                .orElseGet(() -> createDemoHouse());

        AppUser demoUser = createDemoUser(demoHousehold.getId());
        energyHouseService.replaceAssignmentsForUser(demoUser.getId(), Map.of(demoHouse.getId(), HouseAccessRole.VIEWER));

        seedDemoFinanceData(demoHousehold.getId());
        seedDemoEnergyData(demoHouse.getId());
    }

    private Household createDemoHousehold() {
        Household household = new Household();
        household.setName(DEMO_HOUSEHOLD_NAME);
        household.setDemo(true);
        household.setActive(true);
        return householdRepository.save(household);
    }

    private EnergyHouse createDemoHouse() {
        EnergyHouse house = new EnergyHouse();
        house.setName(DEMO_HOUSE_NAME);
        house.setDisplayOrder(999);
        house.setActive(true);
        house.setNotes("Synthetisches Demo-Haus für Präsentationen");
        return energyHouseRepository.save(house);
    }

    private AppUser createDemoUser(UUID demoHouseholdId) {
        AppUser user = new AppUser();
        user.setUsername(DEMO_USERNAME);
        user.setEmail(DEMO_EMAIL);
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setRoles(Set.of(AppRole.ROLE_DEMO));
        user.setHouseholdId(demoHouseholdId);
        user.setPreferredLocale("de");
        user.setEnabled(true);
        return appUserRepository.save(user);
    }

    private void seedDemoFinanceData(UUID householdId) {
        if (!accountRepository.findByHouseholdIdOrderByAccountTypeAscNameAsc(householdId).isEmpty()) {
            return;
        }

        Account checking = new Account();
        checking.setHouseholdId(householdId);
        checking.setName("Demo Girokonto");
        checking.setAccountType(AccountType.CHECKING);
        checking.setBankName("Musterbank AG");
        checking.setDisplayOrder(10);
        checking.setInitialBalance(new BigDecimal("3200.00"));
        checking = accountRepository.save(checking);

        Account savings = new Account();
        savings.setHouseholdId(householdId);
        savings.setName("Demo Tagesgeld");
        savings.setAccountType(AccountType.SAVINGS);
        savings.setBankName("Musterbank AG");
        savings.setDisplayOrder(20);
        savings.setSubsequentInterestRate(new BigDecimal("3.1000"));
        savings.setInitialBalance(new BigDecimal("18500.00"));
        savings = accountRepository.save(savings);

        Account depot = new Account();
        depot.setHouseholdId(householdId);
        depot.setName("Demo ETF-Depot");
        depot.setAccountType(AccountType.INVESTMENT);
        depot.setBankName("Direktbroker");
        depot.setDisplayOrder(30);
        depot.setInitialBalance(new BigDecimal("24000.00"));
        depot = accountRepository.save(depot);

        LocalDate today = LocalDate.now().withDayOfMonth(1);
        snapshotService.recordSnapshot(checking.getId(),
                new SnapshotValue(today.minusMonths(2), new BigDecimal("2950.00"), null, null, null, "Demo"));
        snapshotService.recordSnapshot(checking.getId(),
                new SnapshotValue(today.minusMonths(1), new BigDecimal("3420.00"), null, null, null, "Demo"));
        snapshotService.recordSnapshot(savings.getId(),
                new SnapshotValue(today.minusMonths(2), new BigDecimal("17500.00"), null, null, null, "Demo"));
        snapshotService.recordSnapshot(savings.getId(),
                new SnapshotValue(today.minusMonths(1), new BigDecimal("18500.00"), null, null, null, "Demo"));
        snapshotService.recordSnapshot(depot.getId(),
                new SnapshotValue(today.minusMonths(2), new BigDecimal("22800.00"), null, null,
                        new BigDecimal("21000.00"), "Demo"));
        snapshotService.recordSnapshot(depot.getId(),
                new SnapshotValue(today.minusMonths(1), new BigDecimal("24650.00"), null, null,
                        new BigDecimal("21500.00"), "Demo"));

        Category groceries = new Category();
        groceries.setHouseholdId(householdId);
        groceries.setName("Lebensmittel");
        categoryRepository.save(groceries);

        Person borrower = new Person();
        borrower.setHouseholdId(householdId);
        borrower.setName("Max Mustermann");
        borrower.setRole(PersonRole.BORROWER);
        borrower = personRepository.save(borrower);

        LoanContract loan = new LoanContract();
        loan.setHouseholdId(householdId);
        loan.setDesignation("Demo Auto-Darlehen");
        loan.setOriginalAmount(new BigDecimal("6000.00"));
        loan.setInitialRepaid(new BigDecimal("1800.00"));
        loan.setAnnualInterestRate(new BigDecimal("1.5000"));
        loan.setStartDate(today.minusMonths(12));
        loan.setActive(true);
        loan.setDebtorIds(Set.of(borrower.getId()));
        loanContractRepository.save(loan);
    }

    private void seedDemoEnergyData(UUID houseId) {
        if (!meterRepository.findByHouseIdOrderByDisplayOrderAscNameAsc(houseId).isEmpty()) {
            return;
        }

        EnergyAsset pv = new EnergyAsset();
        pv.setHouseId(houseId);
        pv.setName("Demo PV-Anlage Süd");
        pv.setInstallationDate(LocalDate.now().minusYears(2));
        pv.setInvestmentCost(new BigDecimal("14500.00"));
        pv.setInitialAmortized(new BigDecimal("1200.00"));
        energyAssetRepository.save(pv);

        Meter gridImport = createDemoMeter(houseId, "Demo Netzbezug", MeterType.GRID_IMPORT, "kWh", 10);
        Meter gridExport = createDemoMeter(houseId, "Demo Einspeisung", MeterType.GRID_EXPORT, "kWh", 20);
        Meter pvGen = createDemoMeter(houseId, "Demo PV-Erzeugung", MeterType.PV_GENERATION, "kWh", 30);

        LocalDate tariffDate = LocalDate.now().minusYears(1);
        createDemoTariff(houseId, MeterType.GRID_IMPORT, tariffDate, new BigDecimal("0.3200"));
        createDemoTariff(houseId, MeterType.GRID_EXPORT, tariffDate, new BigDecimal("0.0820"));

        LocalDate d1 = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        LocalDate d2 = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        meterReadingService.recordReading(gridImport.getId(), d1, new BigDecimal("4100"), "Demo");
        meterReadingService.recordReading(gridImport.getId(), d2, new BigDecimal("4320"), "Demo");
        meterReadingService.recordReading(gridExport.getId(), d1, new BigDecimal("3200"), "Demo");
        meterReadingService.recordReading(gridExport.getId(), d2, new BigDecimal("3680"), "Demo");
        meterReadingService.recordReading(pvGen.getId(), d1, new BigDecimal("5400"), "Demo");
        meterReadingService.recordReading(pvGen.getId(), d2, new BigDecimal("6120"), "Demo");
    }

    private Meter createDemoMeter(UUID houseId, String name, MeterType type, String unit, int order) {
        Meter meter = new Meter();
        meter.setHouseId(houseId);
        meter.setName(name);
        meter.setType(type);
        meter.setUnit(unit);
        meter.setDisplayOrder(order);
        meter.setActive(true);
        return meterRepository.save(meter);
    }

    private void createDemoTariff(UUID houseId, MeterType type, LocalDate validFrom, BigDecimal price) {
        EnergyTariff tariff = new EnergyTariff();
        tariff.setHouseId(houseId);
        tariff.setName("Demo " + type.name());
        tariff.setForMeterType(type);
        tariff.setValidFrom(validFrom);
        tariff.setUnitPriceEur(price);
        energyTariffRepository.save(tariff);
    }
}
