package de.in.mp.ui.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vaadin.flow.component.UI;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.BankLookupService;

class AccountEditorDialogTest {

    private AccountService accountService;
    private AccountEditorDialog dialog;

    @BeforeEach
    void setUp() {
        UI.setCurrent(new UI());
        accountService = mock(AccountService.class);
        when(accountService.findAll()).thenReturn(List.of());
        dialog = new AccountEditorDialog(accountService, mock(BankLookupService.class));
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    @Test
    void savesNewRegularAccountWithoutManualNameUsingAccountTypeLabel() {
        dialog.initForCreate(() -> {
        });
        dialog.accountType.setValue(AccountType.INVESTMENT);

        assertThat(dialog.name.isVisible()).isFalse();
        dialog.save();

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountService).save(saved.capture());
        assertThat(saved.getValue().getName()).isNotBlank();
        assertThat(saved.getValue().getAccountType()).isEqualTo(AccountType.INVESTMENT);
        assertThat(saved.getValue().getDisplayOrder()).isEqualTo(Account.DEFAULT_DISPLAY_ORDER);
    }

    @Test
    void requiresNameOnlyWhenAccountTypeIsInstitution() {
        dialog.initForCreate(() -> {
        });
        dialog.accountType.setValue(AccountType.INSTITUTION);

        assertThat(dialog.name.isVisible()).isTrue();
        dialog.save();

        verify(accountService, never()).save(any());
        assertThat(dialog.validationSummary()).contains("name-required");

        dialog.name.setValue("C24");
        dialog.save();

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountService).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("C24");
        assertThat(saved.getValue().getAccountType()).isEqualTo(AccountType.INSTITUTION);
    }

    @Test
    void reportsMissingAccountTypeInsteadOfFailingSilently() {
        dialog.initForCreate(() -> {
        });

        dialog.save();

        verify(accountService, never()).save(any());
        assertThat(dialog.validationSummary()).contains("type-required");
    }

    @Test
    void interestRateFieldsOnlyVisibleForSavingsAccount() {
        dialog.initForCreate(() -> {
        });

        dialog.accountType.setValue(AccountType.CHECKING);
        assertThat(dialog.promotionalInterestRate.isVisible()).isFalse();
        assertThat(dialog.promotionalUntil.isVisible()).isFalse();
        assertThat(dialog.subsequentInterestRate.isVisible()).isFalse();

        dialog.accountType.setValue(AccountType.SAVINGS);
        assertThat(dialog.promotionalInterestRate.isVisible()).isTrue();
        assertThat(dialog.promotionalUntil.isVisible()).isTrue();
        assertThat(dialog.subsequentInterestRate.isVisible()).isTrue();

        dialog.promotionalInterestRate.setValue(new BigDecimal("3.50"));
        dialog.promotionalUntil.setValue(LocalDate.of(2026, 6, 30));
        dialog.subsequentInterestRate.setValue(new BigDecimal("1.75"));

        dialog.accountType.setValue(AccountType.INVESTMENT);
        assertThat(dialog.promotionalInterestRate.isVisible()).isFalse();
        assertThat(dialog.promotionalUntil.isVisible()).isFalse();
        assertThat(dialog.subsequentInterestRate.isVisible()).isFalse();
        assertThat(dialog.promotionalInterestRate.getValue()).isNull();
        assertThat(dialog.promotionalUntil.getValue()).isNull();
        assertThat(dialog.subsequentInterestRate.getValue()).isNull();
    }

    @Test
    void rejectsNegativeInterestRates() {
        dialog.initForCreate(() -> {
        });
        dialog.accountType.setValue(AccountType.SAVINGS);
        dialog.promotionalInterestRate.setValue(new BigDecimal("-1.00"));

        dialog.save();

        verify(accountService, never()).save(any());
        assertThat(dialog.validationSummary()).contains("interest-negative");
    }

    @Test
    void savesSavingsAccountWithInterestRates() {
        dialog.initForCreate(() -> {
        });
        dialog.accountType.setValue(AccountType.SAVINGS);
        dialog.promotionalInterestRate.setValue(new BigDecimal("3.50"));
        LocalDate promoUntil = LocalDate.of(2026, 12, 31);
        dialog.promotionalUntil.setValue(promoUntil);
        dialog.subsequentInterestRate.setValue(new BigDecimal("1.50"));

        dialog.save();

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountService).save(saved.capture());
        assertThat(saved.getValue().getPromotionalInterestRate()).isEqualByComparingTo("3.50");
        assertThat(saved.getValue().getPromotionalUntil()).isEqualTo(promoUntil);
        assertThat(saved.getValue().getSubsequentInterestRate()).isEqualByComparingTo("1.50");
    }
}
