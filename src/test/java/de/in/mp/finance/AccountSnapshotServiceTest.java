package de.in.mp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountSnapshotService} with an in-memory snapshot store.
 */
class AccountSnapshotServiceTest {

    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 31);

    private final List<AccountSnapshot> store = new ArrayList<>();
    private AccountRepository accountRepository;
    private AccountSnapshotService service;
    private Account checking;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        AccountSnapshotRepository snapshotRepository = mock(AccountSnapshotRepository.class);
        service = new AccountSnapshotService(snapshotRepository, accountRepository);

        checking = account(AccountType.CHECKING);
        when(accountRepository.findById(checking.getId())).thenReturn(Optional.of(checking));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(snapshotRepository.save(any(AccountSnapshot.class))).thenAnswer(invocation -> {
            AccountSnapshot snapshot = invocation.getArgument(0);
            if (snapshot.getId() == null) {
                snapshot.setId(UUID.randomUUID());
                store.add(snapshot);
            }
            return snapshot;
        });
        when(snapshotRepository.findByAccountIdAndSnapshotDate(any(), any())).thenAnswer(invocation ->
                store.stream()
                        .filter(s -> s.getAccountId().equals(invocation.getArgument(0))
                                && s.getSnapshotDate().equals(invocation.getArgument(1)))
                        .findFirst());
        when(snapshotRepository.findFirstByAccountIdOrderBySnapshotDateDesc(any())).thenAnswer(invocation ->
                store.stream()
                        .filter(s -> s.getAccountId().equals(invocation.getArgument(0)))
                        .max(Comparator.comparing(AccountSnapshot::getSnapshotDate)));
        when(snapshotRepository.findAllByOrderBySnapshotDateDesc()).thenAnswer(invocation ->
                store.stream().sorted(Comparator.comparing(AccountSnapshot::getSnapshotDate).reversed()).toList());
    }

    @Test
    void recordSnapshotSynchronizesCurrentBalanceWithLatestDate() {
        service.recordSnapshot(checking.getId(), SnapshotValue.ofBalance(OCTOBER, new BigDecimal("1200.00")));
        service.recordSnapshot(checking.getId(), SnapshotValue.ofBalance(SEPTEMBER, new BigDecimal("900.00")));

        assertThat(checking.getCurrentBalance()).isEqualByComparingTo("1200.00");
    }

    @Test
    void recordSnapshotUpsertsAndStoresFlowsAsPositiveMagnitudes() {
        service.recordSnapshot(checking.getId(), new SnapshotValue(OCTOBER, new BigDecimal("1000"),
                new BigDecimal("500"), new BigDecimal("-300"), "first"));
        service.recordSnapshot(checking.getId(), SnapshotValue.ofBalance(OCTOBER, new BigDecimal("1100")));

        assertThat(store).hasSize(1);
        AccountSnapshot snapshot = store.get(0);
        assertThat(snapshot.getBalance()).isEqualByComparingTo("1100.00");
        assertThat(snapshot.getInflow()).isEqualByComparingTo("500.00");
        assertThat(snapshot.getOutflow()).isEqualByComparingTo("300.00");
        assertThat(snapshot.getNotes()).isEqualTo("first");
    }

    @Test
    void recordSnapshotRejectsInstitutions() {
        Account institution = account(AccountType.INSTITUTION);
        when(accountRepository.findById(institution.getId())).thenReturn(Optional.of(institution));

        assertThatThrownBy(() -> service.recordSnapshot(institution.getId(),
                SnapshotValue.ofBalance(OCTOBER, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Institutions");
    }

    @Test
    void recordSnapshotRequiresDateAndBalance() {
        assertThatThrownBy(() -> service.recordSnapshot(checking.getId(),
                SnapshotValue.ofBalance(null, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordSnapshot(checking.getId(),
                SnapshotValue.ofBalance(OCTOBER, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordBatchSkipsEmptyValues() {
        Map<UUID, BigDecimal> values = new LinkedHashMap<>();
        values.put(checking.getId(), new BigDecimal("50"));
        values.put(UUID.randomUUID(), null);

        int saved = service.recordBatch(OCTOBER, values);

        assertThat(saved).isEqualTo(1);
        assertThat(store).hasSize(1);
    }

    @Test
    void recordSnapshotsSynchronizesBalanceOnce() {
        int saved = service.recordSnapshots(checking.getId(), List.of(
                SnapshotValue.ofBalance(SEPTEMBER, new BigDecimal("10")),
                SnapshotValue.ofBalance(OCTOBER, new BigDecimal("20"))));

        assertThat(saved).isEqualTo(2);
        assertThat(checking.getCurrentBalance()).isEqualByComparingTo("20");
        verify(accountRepository, never()).findAll();
    }

    @Test
    void buildDateRowsGroupsSnapshotsNewestFirst() {
        Account savings = account(AccountType.SAVINGS);
        when(accountRepository.findById(savings.getId())).thenReturn(Optional.of(savings));
        service.recordSnapshot(checking.getId(), SnapshotValue.ofBalance(SEPTEMBER, new BigDecimal("100")));
        service.recordSnapshot(checking.getId(), SnapshotValue.ofBalance(OCTOBER, new BigDecimal("200")));
        service.recordSnapshot(savings.getId(), SnapshotValue.ofBalance(OCTOBER, new BigDecimal("300")));

        List<BalanceDateRow> rows = service.buildDateRows();

        assertThat(rows).extracting(BalanceDateRow::date).containsExactly(OCTOBER, SEPTEMBER);
        assertThat(rows.get(0).total()).isEqualByComparingTo("500");
        assertThat(rows.get(1).snapshotFor(savings.getId())).isNull();
    }

    @Test
    void expectedBalanceAddsInflowAndSubtractsOutflow() {
        assertThat(AccountSnapshotService.expectedBalance(new BigDecimal("1000.00"),
                new BigDecimal("500.00"), new BigDecimal("-300.00")))
                .isEqualByComparingTo("1200.00");
    }

    @Test
    void recordBatchStoresAndClearsOptionalInvestedCapitalForInvestmentAccounts() {
        Account depot = account(AccountType.INVESTMENT);
        when(accountRepository.findById(depot.getId())).thenReturn(Optional.of(depot));

        service.recordBatch(OCTOBER,
                Map.of(depot.getId(), new BigDecimal("52210.00")),
                Map.of(depot.getId(), new BigDecimal("48000.00")));

        AccountSnapshot stored = store.get(0);
        assertThat(stored.getInvestedCapital()).isEqualByComparingTo("48000.00");

        // Regular batch without investedCapital map preserves existing investedCapital
        service.recordBatch(OCTOBER, Map.of(depot.getId(), new BigDecimal("53000.00")));
        assertThat(stored.getInvestedCapital()).isEqualByComparingTo("48000.00");

        // Explicit null in investedCapital map clears previously stored value
        Map<UUID, BigDecimal> cleared = new java.util.HashMap<>();
        cleared.put(depot.getId(), null);
        service.recordBatch(OCTOBER, Map.of(depot.getId(), new BigDecimal("53000.00")), cleared);
        assertThat(stored.getInvestedCapital()).isNull();
    }

    @Test
    void investmentPerformanceComputesGainAndReturnSafely() {
        InvestmentPerformance positive = InvestmentPerformance.of(
                new BigDecimal("52210.00"), new BigDecimal("48000.00")).orElseThrow();
        assertThat(positive.gain()).isEqualByComparingTo("4210.00");
        assertThat(positive.returnPercent()).isEqualByComparingTo("8.8");
        assertThat(positive.isPositive()).isTrue();
        assertThat(positive.format(java.util.Locale.GERMANY)).contains("+4.210,00").contains("+8,8 %");

        InvestmentPerformance zeroCostBasis = InvestmentPerformance.of(
                new BigDecimal("1000.00"), BigDecimal.ZERO).orElseThrow();
        assertThat(zeroCostBasis.gain()).isEqualByComparingTo("1000.00");
        assertThat(zeroCostBasis.returnPercent()).isNull();

        assertThat(InvestmentPerformance.of(new BigDecimal("1000.00"), null)).isEmpty();
    }

    private static Account account(AccountType type) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName("Test " + type.name());
        account.setAccountType(type);
        return account;
    }
}
