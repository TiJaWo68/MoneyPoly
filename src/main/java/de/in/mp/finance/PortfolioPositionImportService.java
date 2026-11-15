package de.in.mp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.importer.ImportedDepotPosition;
import de.in.mp.importer.PdfTextExtractor;
import de.in.mp.importer.TradeRepublicStatementParser;

/**
 * Imports brokerage positions from a Trade Republic {@code VERMÖGENSÜBERSICHT}
 * PDF into an {@link AccountType#INVESTMENT} depot, matching existing holdings
 * by normalized ISIN, consolidating multi-tranche positions with weighted
 * average buy-in prices and performing a full sync cleanup of sold holdings.
 */
@Service
public class PortfolioPositionImportService {

    private static final int PRICE_SCALE = 4;

    private final PortfolioPositionRepository positionRepository;
    private final PortfolioPositionService positionService;
    private final MarketPriceProvider marketPriceProvider;
    private final PdfTextExtractor pdfTextExtractor;
    private final TradeRepublicStatementParser statementParser;

    @Autowired
    public PortfolioPositionImportService(
            PortfolioPositionRepository positionRepository,
            PortfolioPositionService positionService,
            MarketPriceProvider marketPriceProvider,
            PdfTextExtractor pdfTextExtractor,
            TradeRepublicStatementParser statementParser) {
        this.positionRepository = positionRepository;
        this.positionService = positionService;
        this.marketPriceProvider = marketPriceProvider;
        this.pdfTextExtractor = pdfTextExtractor;
        this.statementParser = statementParser;
    }

    @Transactional
    public DepotImportResult importTradeRepublicPdf(UUID accountId, byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF payload must not be empty");
        }
        Account depot = positionService.requireInvestmentAccount(accountId);
        String text = pdfTextExtractor.extract(pdfBytes);
        if (!statementParser.supports(text)) {
            throw new IllegalArgumentException("Unsupported depot statement PDF format");
        }
        List<ImportedDepotPosition> parsed = statementParser.parse(text);
        Map<String, ImportedDepotPosition> importedByIsin = mergeDuplicatePdfRows(parsed);

        List<PortfolioPosition> existing = positionRepository.findByAccountIdOrderByNameAsc(depot.getId());
        Map<String, List<PortfolioPosition>> existingByIsin = groupExistingByIsin(existing);
        Set<UUID> retainedIds = new HashSet<>();

        int created = 0;
        int updated = 0;
        int consolidated = 0;

        for (ImportedDepotPosition entry : importedByIsin.values()) {
            List<PortfolioPosition> matches = existingByIsin.getOrDefault(entry.isin(), List.of());
            if (matches.isEmpty()) {
                positionRepository.save(buildNewPosition(depot.getId(), entry));
                created++;
            } else {
                PortfolioPosition survivor = consolidateOrUpdate(matches, entry);
                retainedIds.add(survivor.getId());
                positionRepository.save(survivor);
                updated++;
                if (matches.size() > 1) {
                    consolidated += matches.size() - 1;
                }
            }
        }

        List<PortfolioPosition> toDelete = existing.stream()
                .filter(pos -> !retainedIds.contains(pos.getId()))
                .toList();
        if (!toDelete.isEmpty()) {
            positionRepository.deleteAll(toDelete);
        }
        int deleted = Math.max(0, toDelete.size() - consolidated);

        return new DepotImportResult(created, updated, consolidated, deleted, importedByIsin.size());
    }

    private PortfolioPosition buildNewPosition(UUID depotId, ImportedDepotPosition entry) {
        PortfolioPosition position = new PortfolioPosition();
        position.setAccountId(depotId);
        position.setName(entry.name());
        position.setIsin(entry.isin());
        position.setQuantity(entry.quantity());
        position.setCurrency(PortfolioPosition.DEFAULT_CURRENCY);
        marketPriceProvider.resolveTickerByIsin(entry.isin()).ifPresent(position::setTicker);
        applyPdfStartPrice(position, entry.unitPriceEur());
        return position;
    }

    private PortfolioPosition consolidateOrUpdate(List<PortfolioPosition> matches, ImportedDepotPosition entry) {
        PortfolioPosition survivor = matches.get(0);
        if (matches.size() > 1) {
            survivor.setBuyInPrice(computeWeightedAverageBuyIn(matches));
            if (survivor.getTicker() == null || survivor.getTicker().isBlank()) {
                matches.stream()
                        .map(PortfolioPosition::getTicker)
                        .filter(t -> t != null && !t.isBlank())
                        .findFirst()
                        .ifPresent(survivor::setTicker);
            }
        }
        if (survivor.getName() == null || survivor.getName().isBlank()) {
            survivor.setName(entry.name());
        }
        if (survivor.getTicker() == null || survivor.getTicker().isBlank()) {
            marketPriceProvider.resolveTickerByIsin(entry.isin()).ifPresent(survivor::setTicker);
        }
        survivor.setQuantity(entry.quantity());
        if (!survivor.isManualPriceOverride() && entry.unitPriceEur() != null) {
            if (survivor.getLastPriceEur() == null
                    || PortfolioPosition.DEFAULT_CURRENCY.equalsIgnoreCase(survivor.getCurrency())) {
                applyPdfStartPrice(survivor, entry.unitPriceEur());
            }
        }
        return survivor;
    }

    private static void applyPdfStartPrice(PortfolioPosition position, BigDecimal unitPriceEur) {
        if (unitPriceEur == null) {
            return;
        }
        position.setCurrency(PortfolioPosition.DEFAULT_CURRENCY);
        BigDecimal scaled = unitPriceEur.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        position.setLastPrice(scaled);
        position.setLastPriceEur(scaled);
        position.setFxRate(BigDecimal.ONE);
        position.setPriceUpdatedAt(Instant.now());
    }

    private static BigDecimal computeWeightedAverageBuyIn(List<PortfolioPosition> tranches) {
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (PortfolioPosition tranche : tranches) {
            BigDecimal qty = tranche.getQuantity();
            BigDecimal buyIn = tranche.getBuyInPrice();
            if (qty != null && qty.signum() > 0 && buyIn != null) {
                totalCost = totalCost.add(qty.multiply(buyIn));
                totalQty = totalQty.add(qty);
            }
        }
        if (totalQty.signum() <= 0) {
            return null;
        }
        return totalCost.divide(totalQty, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static Map<String, ImportedDepotPosition> mergeDuplicatePdfRows(List<ImportedDepotPosition> parsed) {
        Map<String, ImportedDepotPosition> merged = new LinkedHashMap<>();
        for (ImportedDepotPosition item : parsed) {
            String isin = normalizeIsin(item.isin());
            if (isin == null) {
                continue;
            }
            merged.merge(isin,
                    new ImportedDepotPosition(item.name(), isin, item.quantity(), item.unitPriceEur(),
                            item.marketValueEur()),
                    (first, second) -> new ImportedDepotPosition(
                            first.name(),
                            isin,
                            first.quantity().add(second.quantity()),
                            first.unitPriceEur() != null ? first.unitPriceEur() : second.unitPriceEur(),
                            sumNullable(first.marketValueEur(), second.marketValueEur())));
        }
        return merged;
    }

    private static BigDecimal sumNullable(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a.add(b);
    }

    private static Map<String, List<PortfolioPosition>> groupExistingByIsin(List<PortfolioPosition> existing) {
        Map<String, List<PortfolioPosition>> map = new HashMap<>();
        for (PortfolioPosition pos : existing) {
            String isin = normalizeIsin(pos.getIsin());
            if (isin != null) {
                map.computeIfAbsent(isin, k -> new ArrayList<>()).add(pos);
            }
        }
        return map;
    }

    private static String normalizeIsin(String isin) {
        if (isin == null) {
            return null;
        }
        String normalized = isin.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
