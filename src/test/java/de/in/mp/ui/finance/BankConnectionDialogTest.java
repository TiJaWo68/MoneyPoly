package de.in.mp.ui.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.data.provider.Query;

import de.in.mp.finance.Account;
import de.in.mp.finance.AccountService;
import de.in.mp.finance.AccountType;
import de.in.mp.finance.banksync.BankInstitution;
import de.in.mp.finance.banksync.BankSyncProvider;
import de.in.mp.finance.banksync.BankSyncService;

class BankConnectionDialogTest {

    @Test
    void placesHouseholdAccountBanksFirstAsFavoritesFollowedBySeparatorAndAlphabeticalRest() {
        List<BankInstitution> unsortedApiInstitutions = List.of(
                inst("dnb", "DNB", "DNBANOKK"),
                inst("sydbank", "Sydbank", "SYBKDE22"),
                inst("targobank", "Targobank", "CMCIDEDD"),
                inst("revolut", "Revolut", "REVOLT21"),
                inst("c24", "C24 Bank", "C24BDEFF"),
                inst("bethmann", "Bethmann Bank", "DELBDE33"),
                inst("n26", "N26", "NTSBDEB1"));

        List<BankInstitution> ordered = BankConnectionDialog.orderInstitutionsWithFavorites(
                unsortedApiInstitutions, Set.of("C24", "Targobank"));

        assertThat(ordered).hasSize(8);
        // Favorites first, alphabetically
        assertThat(ordered.get(0).name()).isEqualTo("C24 Bank");
        assertThat(ordered.get(1).name()).isEqualTo("Targobank");
        // Separator between favorites and others
        assertThat(BankConnectionDialog.isSeparator(ordered.get(2))).isTrue();
        // Remaining institutions sorted alphabetically A-Z
        assertThat(ordered.subList(3, ordered.size()))
                .extracting(BankInstitution::name)
                .containsExactly("Bethmann Bank", "DNB", "N26", "Revolut", "Sydbank");
    }

    @Test
    void omitsSeparatorWhenNoFavoritesOrNoOtherBanksExist() {
        List<BankInstitution> institutions = List.of(
                inst("n26", "N26", "NTSBDEB1"),
                inst("dnb", "DNB", "DNBANOKK"));

        List<BankInstitution> withoutFavorites = BankConnectionDialog.orderInstitutionsWithFavorites(
                institutions, Set.of("Sparkasse"));
        assertThat(withoutFavorites)
                .noneMatch(BankConnectionDialog::isSeparator)
                .extracting(BankInstitution::name)
                .containsExactly("DNB", "N26");

        List<BankInstitution> allFavorites = BankConnectionDialog.orderInstitutionsWithFavorites(
                institutions, Set.of("N26", "DNB"));
        assertThat(allFavorites)
                .noneMatch(BankConnectionDialog::isSeparator)
                .extracting(BankInstitution::name)
                .containsExactly("DNB", "N26");
    }

    @Test
    void initializesDialogWithSortedInstitutionsAndGuardsAgainstSelectingSeparator() {
        BankSyncService bankSyncService = mock(BankSyncService.class);
        AccountService accountService = mock(AccountService.class);
        BankSyncProvider provider = mock(BankSyncProvider.class);

        UUID householdId = UUID.randomUUID();
        Account checking = new Account();
        checking.setId(UUID.randomUUID());
        checking.setName("Main Checking");
        checking.setBankName("C24");
        checking.setAccountType(AccountType.CHECKING);
        checking.setActive(true);

        Account ingInstitution = new Account();
        ingInstitution.setId(UUID.randomUUID());
        ingInstitution.setName("ING");
        ingInstitution.setAccountType(AccountType.INSTITUTION);
        ingInstitution.setActive(true);

        Account ingChecking = new Account();
        ingChecking.setId(UUID.randomUUID());
        ingChecking.setParentId(ingInstitution.getId());
        ingChecking.setName("Extra-Konto");
        ingChecking.setAccountType(AccountType.SAVINGS);
        ingChecking.setActive(true);

        // Orphaned active INSTITUTION row with only deactivated child accounts must NOT become a favorite
        Account bunqInstitution = new Account();
        bunqInstitution.setId(UUID.randomUUID());
        bunqInstitution.setName("bunq");
        bunqInstitution.setAccountType(AccountType.INSTITUTION);
        bunqInstitution.setActive(true);

        Account bunqInactive = new Account();
        bunqInactive.setId(UUID.randomUUID());
        bunqInactive.setParentId(bunqInstitution.getId());
        bunqInactive.setName("bunq Easy");
        bunqInactive.setBankName("bunq");
        bunqInactive.setAccountType(AccountType.CHECKING);
        bunqInactive.setActive(false);

        Account postbankOrphan = new Account();
        postbankOrphan.setId(UUID.randomUUID());
        postbankOrphan.setName("Postbank");
        postbankOrphan.setAccountType(AccountType.INSTITUTION);
        postbankOrphan.setActive(true);

        when(bankSyncService.isEncryptionConfigured()).thenReturn(true);
        when(bankSyncService.getProviders()).thenReturn(List.of(provider));
        when(bankSyncService.findConnectionsByHousehold(householdId)).thenReturn(List.of());
        when(accountService.findAllByHousehold(householdId)).thenReturn(List.of(
                checking, ingInstitution, ingChecking, bunqInstitution, bunqInactive, postbankOrphan));
        when(provider.listInstitutions("DE")).thenReturn(List.of(
                inst("sydbank", "Sydbank", "SYBKDE22"),
                inst("ing", "ING-DiBa", "INGDDEFF"),
                inst("bunq", "bunq", "BUNQNL2A"),
                inst("postbank", "Postbank", "PBNKDEFF"),
                inst("bethmann", "Bethmann Bank", "DELBDE33"),
                inst("c24", "C24 Bank", "C24BDEFF")));

        BankConnectionDialog dialog = new BankConnectionDialog(bankSyncService, accountService);
        dialog.initForHousehold(householdId, () -> {
        });

        List<BankInstitution> renderedItems = dialog.institutionBoxForTest().getDataProvider()
                .fetch(new Query<>()).toList();
        assertThat(renderedItems.get(0).name()).isEqualTo("C24 Bank");
        assertThat(renderedItems.get(1).name()).isEqualTo("ING-DiBa");
        assertThat(BankConnectionDialog.isSeparator(renderedItems.get(2))).isTrue();
        assertThat(renderedItems.subList(3, renderedItems.size()))
                .extracting(BankInstitution::name)
                .containsExactly("Bethmann Bank", "bunq", "Postbank", "Sydbank");

        // Default selection is first favorite bank
        assertThat(dialog.institutionBoxForTest().getValue().name()).isEqualTo("C24 Bank");

        // Attempting to select the separator reverts cleanly to previous bank
        dialog.institutionBoxForTest().setValue(BankConnectionDialog.INSTITUTION_SEPARATOR);
        assertThat(dialog.institutionBoxForTest().getValue().name()).isEqualTo("C24 Bank");
    }

    @Test
    void preselectsPreferredBankFromAccountEditorContext() {
        BankSyncService bankSyncService = mock(BankSyncService.class);
        AccountService accountService = mock(AccountService.class);
        BankSyncProvider provider = mock(BankSyncProvider.class);

        UUID householdId = UUID.randomUUID();
        Account c24 = new Account();
        c24.setId(UUID.randomUUID());
        c24.setName("Giro");
        c24.setBankName("C24");
        c24.setAccountType(AccountType.CHECKING);
        c24.setActive(true);

        when(bankSyncService.isEncryptionConfigured()).thenReturn(true);
        when(bankSyncService.getProviders()).thenReturn(List.of(provider));
        when(bankSyncService.findConnectionsByHousehold(householdId)).thenReturn(List.of());
        when(accountService.findAllByHousehold(householdId)).thenReturn(List.of(c24));
        when(provider.listInstitutions("DE")).thenReturn(List.of(
                inst("c24", "C24", "C24BDEFF"),
                inst("tr", "Trade Republic", "TREBDED1XXX")));

        BankConnectionDialog dialog = new BankConnectionDialog(bankSyncService, accountService);
        dialog.initForHousehold(householdId, "Trade Republic", null, () -> {
        });

        assertThat(dialog.institutionBoxForTest().getValue().name()).isEqualTo("Trade Republic");
    }

    @Test
    void buildsDefaultCallbackUrlFromCurrentBrowserOrigin() throws Exception {
        assertThat(BankConnectionDialog.buildFinanceCallbackUrl(
                URI.create("https://moneypoly-dev:8080/finance?tab=accounts").toURL()))
                .isEqualTo("https://moneypoly-dev:8080/finance");
        assertThat(BankConnectionDialog.buildFinanceCallbackUrl(null))
                .isEqualTo("http://localhost:8080/finance");
    }

    private static BankInstitution inst(String id, String name, String bic) {
        return new BankInstitution(id, name, bic, "DE");
    }
}
