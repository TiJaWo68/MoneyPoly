package de.in.mp.admin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import de.in.mp.energy.MeterReading;
import de.in.mp.energy.MeterReadingPhotoService;
import de.in.mp.energy.MeterReadingRepository;
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
import de.in.mp.finance.PortfolioPosition;
import de.in.mp.finance.PortfolioPositionRepository;
import de.in.mp.finance.SnapshotValue;
import de.in.mp.finance.WealthEvent;
import de.in.mp.finance.WealthEventRepository;
import de.in.mp.loans.LoanContract;
import de.in.mp.loans.LoanContractRepository;
import de.in.mp.loans.LoanRepayment;
import de.in.mp.loans.LoanRepaymentRepository;
import de.in.mp.loans.PaymentMethod;
import de.in.mp.security.AppRole;
import de.in.mp.security.AppUser;
import de.in.mp.security.AppUserRepository;
import de.in.mp.security.AuthProvider;

/**
 * Seeds and maintains a self-contained synthetic demo tenant (demo household,
 * 10-year monthly net worth history with annotated major events, all account
 * types, stock portfolio, private loan repayments, and demo energy house) once
 * initial setup has created at least one administrator account.
 */
@Service
public class DemoSeedService implements ApplicationRunner {

    public static final String DEMO_USERNAME = "demo";
    public static final String DEMO_HOUSEHOLD_NAME = "Demo-Familie";
    public static final String DEMO_HOUSE_NAME = "Demo-Haus";

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoSeedService.class);
    private static final String DEMO_EMAIL = "demo@moneypoly.local";

    private static final int HISTORY_MONTHS = 120;
    private static final int MONTH_DEPOT_START = 115;
    private static final int MONTH_DEPOT_TOPUP = 111;
    private static final int MONTH_CAR_BEFORE_HOUSE = 96;
    private static final int MONTH_HOUSE_PURCHASE = 66;
    private static final int MONTH_PV_ASSET = 36;
    private static final int MONTH_PRIVATE_LOAN = 12;
    private static final int MONTH_EV_AFTER_PV = 8;

    private static final BigDecimal HOUSE_LOAN_START = new BigDecimal("32000.00");
    private static final BigDecimal HOUSE_LOAN_MONTHLY_AMORTIZATION = new BigDecimal("450.00");
    private static final BigDecimal DEPOT_FINAL_COST_BASIS = new BigDecimal("33415.00");

    private final AppUserRepository appUserRepository;
    private final HouseholdRepository householdRepository;
    private final EnergyHouseRepository energyHouseRepository;
    private final EnergyHouseService energyHouseService;
    private final AccountRepository accountRepository;
    private final AccountSnapshotService snapshotService;
    private final CategoryRepository categoryRepository;
    private final PersonRepository personRepository;
    private final LoanContractRepository loanContractRepository;
    private final LoanRepaymentRepository loanRepaymentRepository;
    private final PortfolioPositionRepository portfolioPositionRepository;
    private final WealthEventRepository wealthEventRepository;
    private final EnergyAssetRepository energyAssetRepository;
    private final MeterRepository meterRepository;
    private final EnergyTariffRepository energyTariffRepository;
    private final MeterReadingRepository meterReadingRepository;
    private final MeterReadingService meterReadingService;
    private final MeterReadingPhotoService meterReadingPhotoService;
    private final PasswordEncoder passwordEncoder;

    @Autowired
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
            LoanRepaymentRepository loanRepaymentRepository,
            PortfolioPositionRepository portfolioPositionRepository,
            WealthEventRepository wealthEventRepository,
            EnergyAssetRepository energyAssetRepository,
            MeterRepository meterRepository,
            EnergyTariffRepository energyTariffRepository,
            MeterReadingRepository meterReadingRepository,
            MeterReadingService meterReadingService,
            MeterReadingPhotoService meterReadingPhotoService,
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
        this.loanRepaymentRepository = loanRepaymentRepository;
        this.portfolioPositionRepository = portfolioPositionRepository;
        this.wealthEventRepository = wealthEventRepository;
        this.energyAssetRepository = energyAssetRepository;
        this.meterRepository = meterRepository;
        this.energyTariffRepository = energyTariffRepository;
        this.meterReadingRepository = meterReadingRepository;
        this.meterReadingService = meterReadingService;
        this.meterReadingPhotoService = meterReadingPhotoService;
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
        java.util.Optional<AppUser> existingDemoUser = appUserRepository.findByUsername(DEMO_USERNAME);
        Household demoHousehold = householdRepository.findByNameIgnoreCase(DEMO_HOUSEHOLD_NAME)
                .or(() -> existingDemoUser
                        .map(AppUser::getHouseholdId)
                        .flatMap(householdRepository::findById)
                        .filter(Household::isDemo))
                .orElseGet(this::createDemoHousehold);
        EnergyHouse demoHouse = energyHouseRepository.findByNameIgnoreCase(DEMO_HOUSE_NAME)
                .orElseGet(this::createDemoHouse);

        AppUser demoUser = existingDemoUser.orElseGet(() -> createDemoUser(demoHousehold.getId()));
        if (!demoHousehold.getId().equals(demoUser.getHouseholdId())) {
            demoUser.setHouseholdId(demoHousehold.getId());
            demoUser = appUserRepository.save(demoUser);
        }

        if (energyHouseService.findAssignmentsMapForUser(demoUser.getId()).isEmpty()) {
            energyHouseService.replaceAssignmentsForUser(
                    demoUser.getId(), Map.of(demoHouse.getId(), HouseAccessRole.VIEWER));
        }

        seedDemoFinanceData(demoHousehold.getId());
        seedDemoEnergyData(demoHouse.getId());
    }

    private Household createDemoHousehold() {
        LOGGER.info("Creating synthetic demo household '{}'", DEMO_HOUSEHOLD_NAME);
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
        LOGGER.info("Creating synthetic demo user '{}'", DEMO_USERNAME);
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
        List<Account> existingAccounts = accountRepository.findByHouseholdIdOrderByAccountTypeAscNameAsc(householdId);
        boolean alreadyUpgraded = !wealthEventRepository
                .findByHouseholdIdOrderByEventDateAscCreatedAtAsc(householdId).isEmpty()
                && existingAccounts.stream().anyMatch(a -> a.getAccountType() == AccountType.LOAN)
                && existingAccounts.stream().anyMatch(a -> a.getAccountType() == AccountType.INSTITUTION);
        if (alreadyUpgraded) {
            return;
        }

        LOGGER.info("Seeding/upgrading 10-year synthetic demo finance data for household {}", householdId);

        Account houseBank = ensureAccount(
                existingAccounts, householdId, "Demo Hausbank AG", null,
                AccountType.INSTITUTION, null, "Demo Hausbank AG", 10, BigDecimal.ZERO);
        Account broker = ensureAccount(
                existingAccounts, householdId, "Demo Direktbroker", null,
                AccountType.INSTITUTION, null, "Demo Direktbroker", 20, BigDecimal.ZERO);

        Account checking = ensureAccount(
                existingAccounts, householdId, "Demo Girokonto", houseBank.getId(),
                AccountType.CHECKING, "Demo Girokonto", "Demo Hausbank AG", 11, new BigDecimal("2400.00"));
        Account savings = ensureAccount(
                existingAccounts, householdId, "Demo Tagesgeld", houseBank.getId(),
                AccountType.SAVINGS, "Demo Tagesgeld", "Demo Hausbank AG", 12, new BigDecimal("32000.00"));
        savings.setPromotionalInterestRate(new BigDecimal("3.5000"));
        savings.setPromotionalUntil(LocalDate.now().plusMonths(6));
        savings.setSubsequentInterestRate(new BigDecimal("2.6000"));
        savings = accountRepository.save(savings);

        Account houseLoan = ensureAccount(
                existingAccounts, householdId, "Demo Haus-Darlehen", houseBank.getId(),
                AccountType.LOAN, null, "Demo Hausbank AG", 13, HOUSE_LOAN_START);

        Account depot = findAccount(existingAccounts, "Demo Aktien- & ETF-Depot", "Demo ETF-Depot");
        if (depot == null) {
            depot = new Account();
            depot.setHouseholdId(householdId);
        }
        depot.setName("Demo Aktien- & ETF-Depot");
        depot.setParentId(broker.getId());
        depot.setAccountType(AccountType.INVESTMENT);
        depot.setBankName("Demo Direktbroker");
        depot.setDisplayOrder(21);
        depot.setInitialBalance(new BigDecimal("18000.00"));
        depot.setActive(true);
        depot = accountRepository.save(depot);

        LocalDate baseMonth = LocalDate.now().withDayOfMonth(1);
        seedTenYearMonthlySnapshots(baseMonth, checking, savings, depot, houseLoan);
        seedDemoPortfolioPositions(depot);

        if (categoryRepository.findByHouseholdIdOrderByNameAsc(householdId).isEmpty()) {
            Category groceries = new Category();
            groceries.setHouseholdId(householdId);
            groceries.setName("Lebensmittel");
            categoryRepository.save(groceries);
        }

        Person borrower = personRepository.findByHouseholdIdOrderByNameAsc(householdId).stream()
                .filter(p -> "Max Mustermann".equals(p.getName()))
                .findFirst()
                .orElseGet(() -> {
                    Person person = new Person();
                    person.setHouseholdId(householdId);
                    person.setName("Max Mustermann");
                    person.setRole(PersonRole.BORROWER);
                    return personRepository.save(person);
                });

        LocalDate loanEventDate = baseMonth.minusMonths(MONTH_PRIVATE_LOAN).minusDays(8);
        LoanContract loan = loanContractRepository.findByHouseholdIdOrderByDesignationAsc(householdId).stream()
                .filter(c -> "Demo Auto-Darlehen".equals(c.getDesignation()))
                .findFirst()
                .orElseGet(LoanContract::new);
        loan.setHouseholdId(householdId);
        loan.setDesignation("Demo Auto-Darlehen");
        loan.setOriginalAmount(new BigDecimal("6000.00"));
        loan.setInitialRepaid(new BigDecimal("1200.00"));
        loan.setAnnualInterestRate(new BigDecimal("1.5000"));
        loan.setStartDate(loanEventDate);
        loan.setActive(true);
        loan.setDebtorIds(Set.of(borrower.getId()));
        loan = loanContractRepository.save(loan);

        seedDemoLoanRepayments(loan, borrower, baseMonth);
        seedDemoWealthEvents(householdId, baseMonth, loanEventDate, loan);
    }

    private void seedTenYearMonthlySnapshots(
            LocalDate baseMonth, Account checking, Account savings, Account depot, Account houseLoan) {
        List<SnapshotValue> checkingSeries = new ArrayList<>(HISTORY_MONTHS + 1);
        List<SnapshotValue> savingsSeries = new ArrayList<>(HISTORY_MONTHS + 1);
        List<SnapshotValue> depotSeries = new ArrayList<>();
        List<SnapshotValue> loanSeries = new ArrayList<>();

        BigDecimal runningSavings = new BigDecimal("32000.00");
        for (int age = HISTORY_MONTHS; age >= 0; age--) {
            LocalDate date = baseMonth.minusMonths(age);
            int step = HISTORY_MONTHS - age;

            if (age < HISTORY_MONTHS) {
                BigDecimal monthlySurplus;
                if (age >= MONTH_DEPOT_TOPUP) {
                    monthlySurplus = new BigDecimal("1500.00");
                } else if (age > MONTH_HOUSE_PURCHASE) {
                    monthlySurplus = new BigDecimal("1850.00");
                } else if (age > MONTH_PV_ASSET) {
                    monthlySurplus = new BigDecimal("950.00");
                } else {
                    monthlySurplus = new BigDecimal("1150.00");
                }
                runningSavings = runningSavings.add(monthlySurplus);
            }

            if (age == MONTH_DEPOT_START) {
                runningSavings = runningSavings.subtract(new BigDecimal("18000.00"));
            } else if (age == MONTH_DEPOT_TOPUP) {
                runningSavings = runningSavings.subtract(new BigDecimal("15415.00"));
            } else if (age == MONTH_CAR_BEFORE_HOUSE) {
                runningSavings = runningSavings.subtract(new BigDecimal("18500.00"));
            } else if (age == MONTH_HOUSE_PURCHASE) {
                runningSavings = runningSavings.subtract(new BigDecimal("46000.00"));
            } else if (age == MONTH_PV_ASSET) {
                runningSavings = runningSavings.subtract(new BigDecimal("15500.00"));
            } else if (age == MONTH_PRIVATE_LOAN) {
                runningSavings = runningSavings.subtract(new BigDecimal("6000.00"));
            } else if (age == MONTH_EV_AFTER_PV) {
                runningSavings = runningSavings.subtract(new BigDecimal("24000.00"));
            }

            BigDecimal checkingBal = BigDecimal.valueOf(2400L + step * 12L + (step % 4) * 180L)
                    .setScale(2, RoundingMode.HALF_UP);
            checkingSeries.add(new SnapshotValue(date, checkingBal, null, null, null, "Demo"));
            savingsSeries.add(new SnapshotValue(date, runningSavings.setScale(2, RoundingMode.HALF_UP), null, null, null, "Demo"));

            if (age <= MONTH_DEPOT_START) {
                SnapshotValue depotSnap = buildDepotSnapshotForAge(date, age);
                depotSeries.add(depotSnap);
            }
            if (age <= MONTH_HOUSE_PURCHASE) {
                int monthsSinceHouse = MONTH_HOUSE_PURCHASE - age;
                BigDecimal remaining = HOUSE_LOAN_START
                        .subtract(HOUSE_LOAN_MONTHLY_AMORTIZATION.multiply(BigDecimal.valueOf(monthsSinceHouse)))
                        .max(BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP);
                loanSeries.add(new SnapshotValue(date, remaining, null, null, null, "Demo"));
            }
        }

        snapshotService.recordSnapshots(checking.getId(), checkingSeries);
        snapshotService.recordSnapshots(savings.getId(), savingsSeries);
        snapshotService.recordSnapshots(depot.getId(), depotSeries);
        snapshotService.recordSnapshots(houseLoan.getId(), loanSeries);
    }

    private SnapshotValue buildDepotSnapshotForAge(LocalDate date, int age) {
        BigDecimal invested = age > MONTH_DEPOT_TOPUP ? new BigDecimal("18000.00") : DEPOT_FINAL_COST_BASIS;
        BigDecimal marketValue;
        if (age > MONTH_DEPOT_TOPUP) {
            int months = MONTH_DEPOT_START - age;
            marketValue = BigDecimal.valueOf(18000L + months * 180L).setScale(2, RoundingMode.HALF_UP);
        } else {
            int heldMonths = MONTH_DEPOT_TOPUP - age;
            double progress = (double) heldMonths / MONTH_DEPOT_TOPUP;
            double baseGrowth = 33415.0 + progress * (49850.0 - 33415.0);
            double dip1 = (age <= 80 && age >= 74) ? -3200.0 * Math.sin(((80 - age) / 6.0) * Math.PI) : 0.0;
            double dip2 = (age <= 45 && age >= 38) ? -4100.0 * Math.sin(((45 - age) / 7.0) * Math.PI) : 0.0;
            double wave = Math.sin(heldMonths * 0.45) * 650.0;
            marketValue = BigDecimal.valueOf(Math.max(32000.0, baseGrowth + dip1 + dip2 + wave))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return new SnapshotValue(date, marketValue, null, null, invested, "Demo");
    }

    private void seedDemoPortfolioPositions(Account depot) {
        if (!portfolioPositionRepository.findByAccountIdOrderByNameAsc(depot.getId()).isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        // 4 blue-chip stocks totaling ~50,326.50 EUR (cost basis 33,415.00 EUR, acquired before house)
        portfolioPositionRepository.saveAll(List.of(
                createStockPosition(depot.getId(), "SAP SE", "DE0007164600", "SAP.DE",
                        "65", "138.00", "218.50", now),
                createStockPosition(depot.getId(), "Allianz SE", "DE0008404005", "ALV.DE",
                        "45", "205.00", "292.00", now),
                createStockPosition(depot.getId(), "Siemens AG", "DE0007236101", "SIE.DE",
                        "65", "126.00", "186.40", now),
                createStockPosition(depot.getId(), "Deutsche Telekom AG", "DE0005557508", "DTE.DE",
                        "380", "18.50", "28.60", now)));
    }

    private static PortfolioPosition createStockPosition(
            UUID accountId, String name, String isin, String ticker,
            String quantity, String buyIn, String priceEur, Instant updatedAt) {
        PortfolioPosition pos = new PortfolioPosition();
        pos.setAccountId(accountId);
        pos.setName(name);
        pos.setIsin(isin);
        pos.setTicker(ticker);
        pos.setCurrency(PortfolioPosition.DEFAULT_CURRENCY);
        pos.setQuantity(new BigDecimal(quantity));
        pos.setBuyInPrice(new BigDecimal(buyIn));
        pos.setLastPrice(new BigDecimal(priceEur));
        pos.setLastPriceEur(new BigDecimal(priceEur));
        pos.setPriceUpdatedAt(updatedAt);
        pos.setManualPriceOverride(true);
        pos.setNotes("Erworben vor Hauskauf (Demo)");
        return pos;
    }

    private void seedDemoLoanRepayments(LoanContract loan, Person borrower, LocalDate baseMonth) {
        if (!loanRepaymentRepository.findByLoanContractIdOrderByRepaymentDateAsc(loan.getId()).isEmpty()) {
            return;
        }
        List<LoanRepayment> repayments = new ArrayList<>();
        for (int m = 10; m >= 2; m -= 2) {
            LoanRepayment rep = new LoanRepayment();
            rep.setLoanContractId(loan.getId());
            rep.setPayerId(borrower.getId());
            rep.setRepaymentDate(baseMonth.minusMonths(m).plusDays(4));
            rep.setPrincipalAmount(new BigDecimal("240.00"));
            rep.setInterestAmount(new BigDecimal("6.50"));
            rep.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
            rep.setNotes("Monatsrate (Demo)");
            repayments.add(rep);
        }
        loanRepaymentRepository.saveAll(repayments);
    }

    private void seedDemoWealthEvents(UUID householdId, LocalDate baseMonth, LocalDate loanEventDate, LoanContract loan) {
        if (!wealthEventRepository.findByHouseholdIdOrderByEventDateAscCreatedAtAsc(householdId).isEmpty()) {
            return;
        }
        // Dates sit inside (prevMonthFirst, targetMonthFirst] so tooltips highlight the exact drop segment.
        wealthEventRepository.saveAll(List.of(
                createWealthEvent(householdId,
                        baseMonth.minusMonths(MONTH_CAR_BEFORE_HOUSE).minusDays(10),
                        new BigDecimal("-18500.00"),
                        "Autokauf: Familienkombi (vor Hauskauf)", null),
                createWealthEvent(householdId,
                        baseMonth.minusMonths(MONTH_HOUSE_PURCHASE).minusDays(10),
                        new BigDecimal("-46000.00"),
                        "Hauskauf: Eigenkapital, Notar & Nebenkosten", null),
                createWealthEvent(householdId,
                        baseMonth.minusMonths(MONTH_PV_ASSET).minusDays(10),
                        new BigDecimal("-15500.00"),
                        "PV-Anlage Süd inkl. Batteriespeicher (Demo-Haus)", null),
                createWealthEvent(householdId,
                        loanEventDate,
                        new BigDecimal("-6000.00"),
                        "Privatkredit: " + loan.getDesignation(), loan.getId()),
                createWealthEvent(householdId,
                        baseMonth.minusMonths(MONTH_EV_AFTER_PV).minusDays(10),
                        new BigDecimal("-24000.00"),
                        "Autokauf: Elektroauto (nach PV-Anlage)", null)));
    }

    private static WealthEvent createWealthEvent(
            UUID householdId, LocalDate date, BigDecimal amount, String description, UUID loanId) {
        WealthEvent ev = new WealthEvent();
        ev.setHouseholdId(householdId);
        ev.setEventDate(date);
        ev.setAmount(amount);
        ev.setDescription(description);
        ev.setLoanContractId(loanId);
        ev.setCreatedAt(Instant.now());
        return ev;
    }

    private void seedDemoEnergyData(UUID houseId) {
        LocalDate baseMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate pvDate = baseMonth.minusMonths(MONTH_PV_ASSET).minusDays(10);
        LocalDate wallboxDate = baseMonth.minusMonths(MONTH_EV_AFTER_PV).minusDays(10);

        List<EnergyAsset> assets = energyAssetRepository.findByHouseIdOrderByInstallationDateAscNameAsc(houseId);
        EnergyAsset pv = assets.stream()
                .filter(a -> "Demo PV-Anlage Süd".equals(a.getName()))
                .findFirst()
                .orElseGet(EnergyAsset::new);
        pv.setHouseId(houseId);
        pv.setName("Demo PV-Anlage Süd");
        pv.setInstallationDate(pvDate);
        pv.setInvestmentCost(new BigDecimal("15500.00"));
        pv.setInitialAmortized(new BigDecimal("1850.00"));
        energyAssetRepository.save(pv);

        if (assets.stream().noneMatch(a -> "Demo Wallbox Garage".equals(a.getName()))) {
            EnergyAsset wallbox = new EnergyAsset();
            wallbox.setHouseId(houseId);
            wallbox.setName("Demo Wallbox Garage");
            wallbox.setInstallationDate(wallboxDate);
            wallbox.setInvestmentCost(new BigDecimal("1850.00"));
            wallbox.setInitialAmortized(new BigDecimal("180.00"));
            energyAssetRepository.save(wallbox);
        }

        List<Meter> existingMeters = meterRepository.findByHouseIdOrderByDisplayOrderAscNameAsc(houseId);
        Meter gridImport = ensureMeter(existingMeters, houseId, "Demo Netzbezug", MeterType.GRID_IMPORT, "kWh", 10);
        Meter gridExport = ensureMeter(existingMeters, houseId, "Demo Einspeisung", MeterType.GRID_EXPORT, "kWh", 20);
        Meter pvGen = ensureMeter(existingMeters, houseId, "Demo PV-Erzeugung", MeterType.PV_GENERATION, "kWh", 30);

        seedDemoTariffs(houseId, baseMonth);

        List<MeterReading> existingImportReadings = meterReadingRepository.findByMeterIdOrderByReadingDateAsc(gridImport.getId());
        if (existingImportReadings.size() < 6) {
            for (int m = 12; m >= 1; m--) {
                LocalDate readingDate = baseMonth.minusMonths(m);
                int idx = 12 - m;
                meterReadingService.recordReading(gridImport.getId(), readingDate,
                        BigDecimal.valueOf(2800L + idx * 145L), "Demo");
                meterReadingService.recordReading(gridExport.getId(), readingDate,
                        BigDecimal.valueOf(1900L + idx * 165L), "Demo");
                meterReadingService.recordReading(pvGen.getId(), readingDate,
                        BigDecimal.valueOf(3100L + idx * 240L), "Demo");
            }
            List<MeterReading> updated = meterReadingRepository.findByMeterIdOrderByReadingDateAsc(gridImport.getId());
            if (!updated.isEmpty()) {
                MeterReading latest = updated.get(updated.size() - 1);
                seedProofPhotoIfMissing(latest.getId(), latest.getReadingDate());
            }
        }
    }

    private void seedDemoTariffs(UUID houseId, LocalDate baseMonth) {
        List<EnergyTariff> tariffs = energyTariffRepository.findByHouseIdOrderByForMeterTypeAscValidFromDesc(houseId);
        if (tariffs.stream().noneMatch(t -> t.getForMeterType() == MeterType.GRID_IMPORT && t.getValidTo() != null)) {
            LocalDate oldStart = baseMonth.minusYears(3);
            LocalDate oldEnd = baseMonth.minusYears(1).minusDays(1);
            createDemoTariff(houseId, "Demo Grundversorgung (alt)", MeterType.GRID_IMPORT,
                    oldStart, oldEnd, new BigDecimal("0.2850"), new BigDecimal("11.90"));
        }
        if (tariffs.stream().noneMatch(t -> t.getForMeterType() == MeterType.GRID_IMPORT && t.getValidTo() == null)) {
            createDemoTariff(houseId, "Demo Ökostrom", MeterType.GRID_IMPORT,
                    baseMonth.minusYears(1), null, new BigDecimal("0.3200"), new BigDecimal("13.50"));
        }
        if (tariffs.stream().noneMatch(t -> t.getForMeterType() == MeterType.GRID_EXPORT)) {
            createDemoTariff(houseId, "Demo EEG Einspeisevergütung", MeterType.GRID_EXPORT,
                    baseMonth.minusYears(3), null, new BigDecimal("0.0820"), BigDecimal.ZERO);
        }
    }

    private void seedProofPhotoIfMissing(UUID readingId, LocalDate readingDate) {
        if (meterReadingPhotoService.findByReadingId(readingId).isPresent()) {
            return;
        }
        byte[] jpeg = createSyntheticMeterPhoto();
        if (jpeg.length > 0) {
            meterReadingPhotoService.savePhoto(
                    readingId,
                    "demo-zaehlerstand-" + readingDate + ".jpg",
                    "image/jpeg",
                    jpeg);
        }
    }

    private static byte[] createSyntheticMeterPhoto() {
        BufferedImage img = new BufferedImage(320, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(28, 36, 48));
        g.fillRect(0, 0, 320, 120);
        g.setColor(new Color(56, 189, 248));
        g.drawRect(8, 8, 303, 103);
        g.setColor(new Color(148, 163, 184));
        g.fillRect(24, 24, 272, 72);
        g.setColor(new Color(15, 23, 42));
        // Stylized bars representing digital meter display without requiring OS fonts
        for (int i = 0; i < 6; i++) {
            g.fillRect(42 + i * 38, 42, 22, 36);
        }
        g.dispose();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    private Account ensureAccount(
            List<Account> existingAccounts,
            UUID householdId,
            String name,
            UUID parentId,
            AccountType type,
            String legacyNameAlias,
            String bankName,
            int order,
            BigDecimal initialBalance) {
        Account account = findAccount(existingAccounts, name, legacyNameAlias);
        if (account == null) {
            account = new Account();
            account.setHouseholdId(householdId);
        }
        account.setName(name);
        account.setParentId(parentId);
        account.setAccountType(type);
        account.setBankName(bankName);
        account.setDisplayOrder(order);
        account.setInitialBalance(initialBalance);
        account.setActive(true);
        return accountRepository.save(account);
    }

    private static Account findAccount(List<Account> existing, String primaryName, String aliasName) {
        return existing.stream()
                .filter(a -> primaryName.equals(a.getName()) || (aliasName != null && aliasName.equals(a.getName())))
                .findFirst()
                .orElse(null);
    }

    private Meter ensureMeter(
            List<Meter> existing, UUID houseId, String name, MeterType type, String unit, int order) {
        return existing.stream()
                .filter(m -> name.equals(m.getName()))
                .findFirst()
                .orElseGet(() -> createDemoMeter(houseId, name, type, unit, order));
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

    private void createDemoTariff(
            UUID houseId, String name, MeterType type,
            LocalDate validFrom, LocalDate validTo, BigDecimal price, BigDecimal baseFee) {
        EnergyTariff tariff = new EnergyTariff();
        tariff.setHouseId(houseId);
        tariff.setName(name);
        tariff.setForMeterType(type);
        tariff.setValidFrom(validFrom);
        tariff.setValidTo(validTo);
        tariff.setUnitPriceEur(price);
        tariff.setMonthlyBaseFeeEur(baseFee);
        energyTariffRepository.save(tariff);
    }
}
