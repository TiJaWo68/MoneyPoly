package de.in.mp.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Helper utility that scans historical bank statement PDFs, maps extracted
 * balances (including now-inactive accounts) onto {@code finanzen.ods} columns
 * using an external {@code .properties} configuration, reconstructs running
 * balances for accounts without own PDFs via IBAN transfer tracking, and can
 * write the backfilled rows into a target ODS file.
 */
public final class OdsStatementBackfillHelper {

    private static final Pattern TARGO_PERIOD = Pattern.compile(
            "vom\\s+(\\d{2}\\.\\d{2}\\.\\d{4})\\s*-\\s*(\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final Pattern TARGO_TX_LINE = Pattern.compile(
            "^(\\d{2}\\.\\d{2})\\s+[A-ZÄÖÜ]{2}\\s+(.+)$");
    private static final Pattern C24_PERIOD = Pattern.compile(
            "Kontoauszug\\s+\\d{2}/\\d{4}.{0,200}?(\\d{2}\\.\\d{2}\\.\\d{4})\\s*-\\s*(\\d{2}\\.\\d{2}\\.\\d{4})",
            Pattern.DOTALL);
    private static final Pattern C24_TX_LINE = Pattern.compile(
            "^(\\d{2}\\.\\d{2}\\.)\\s+\\d{2}\\.\\d{2}\\.\\s+(.+)$");
    private static final Pattern C24_SIGNED_AMOUNT = Pattern.compile(
            "^([+-])\\s*(\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s*€");
    private static final Pattern GERMAN_AMOUNT = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{3})*,\\d{2})");
    private static final Pattern IBAN_PATTERN = Pattern.compile(
            "([A-Z]{2}\\d{2}(?:\\s?\\d{4}){2,6}(?:\\s?\\d{1,4})?)");

    private static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String DEFAULT_CONFIG_FILENAME = "backfill-config.properties";

    private final PdfTextExtractor pdfTextExtractor;
    private final List<BankStatementParser> parsers;
    private final BackfillConfig config;

    public OdsStatementBackfillHelper(BackfillConfig config) {
        this(new PdfTextExtractor(), List.<BankStatementParser>of(
                new TargobankStatementParser(),
                new C24StatementParser(),
                new ScalableStatementParser(),
                new ChaseStatementParser()), config);
    }

    public OdsStatementBackfillHelper(
            PdfTextExtractor pdfTextExtractor, List<BankStatementParser> parsers, BackfillConfig config) {
        this.pdfTextExtractor = pdfTextExtractor;
        this.parsers = parsers;
        this.config = config;
    }

    public ScanResult scanStatements(Path docsRoot) throws IOException {
        List<Path> pdfs = listPdfs(docsRoot);
        Map<EntryKey, StatementEntry> deduplicated = new LinkedHashMap<>();
        List<String> skippedFiles = new ArrayList<>();
        int duplicatesSkipped = 0;

        for (Path pdf : pdfs) {
            String text;
            try {
                text = pdfTextExtractor.extract(Files.readAllBytes(pdf));
            } catch (RuntimeException ex) {
                skippedFiles.add(pdf.getFileName() + " (read error: " + ex.getMessage() + ")");
                continue;
            }
            Optional<BankStatementParser> parser = parsers.stream()
                    .filter(p -> p.supports(text))
                    .findFirst();
            if (parser.isEmpty()) {
                skippedFiles.add(pdf.getFileName() + " (unsupported format)");
                continue;
            }
            try {
                for (StatementEntry entry : parser.get().parse(text)) {
                    EntryKey key = new EntryKey(entry.importKey(), entry.closingDate());
                    if (deduplicated.containsKey(key)) {
                        duplicatesSkipped++;
                    } else {
                        deduplicated.put(key, entry);
                    }
                }
            } catch (RuntimeException ex) {
                skippedFiles.add(pdf.getFileName() + " (parse error: " + ex.getMessage() + ")");
            }
        }
        return new ScanResult(new ArrayList<>(deduplicated.values()), duplicatesSkipped, skippedFiles);
    }

    public Optional<String> mapToOdsColumn(StatementEntry entry) {
        if (entry.iban() != null) {
            String normalizedIban = entry.iban().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            String mapped = config.ibanToOdsColumn().get(normalizedIban);
            if (mapped != null) {
                return Optional.of(mapped);
            }
        }
        if (entry.accountNumber() != null) {
            String mappedByNumber = config.accountNumberToOdsColumn().get(entry.accountNumber().trim());
            if (mappedByNumber != null) {
                return Optional.of(mappedByNumber);
            }
        }

        String key = entry.importKey() == null ? "" : entry.importKey().toLowerCase(Locale.ROOT);
        String holder = entry.accountHolder() == null ? "" : entry.accountHolder().toLowerCase(Locale.ROOT);
        String label = entry.accountLabel() == null ? "" : entry.accountLabel().toLowerCase(Locale.ROOT);

        if ("Targobank".equalsIgnoreCase(entry.bank())) {
            if (label.contains("giro") || label.contains("best-konto")) {
                return Optional.ofNullable(config.bankDefaultColumns().get("targobank.giro"));
            }
            if (label.contains("tagesgeld") || label.contains("sparbuch")) {
                return Optional.ofNullable(config.bankDefaultColumns().get("targobank.savings"));
            }
        }
        if ("C24".equalsIgnoreCase(entry.bank())) {
            String ownerToken = resolveC24OwnerToken(holder);
            if (ownerToken != null) {
                for (Map.Entry<String, String> override : config.c24LabelOverrides().entrySet()) {
                    String prefix = ownerToken + ".";
                    if (override.getKey().startsWith(prefix)) {
                        String marker = override.getKey().substring(prefix.length());
                        if (label.contains(marker)) {
                            return Optional.of(override.getValue());
                        }
                    }
                }
                String ownerPrefix = config.c24OwnerPrefixes().get(ownerToken);
                if (ownerPrefix != null) {
                    if (label.contains("tagesgeld")) {
                        return Optional.of(ownerPrefix + " / Tagesgeld");
                    }
                    if (label.contains("pocket")) {
                        return Optional.of(ownerPrefix + " / Pocket");
                    }
                    if (label.contains("smartkonto") || label.contains("giro") || key.contains("giro")) {
                        return Optional.of(ownerPrefix + " / Giro");
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String resolveC24OwnerToken(String holderLower) {
        for (String token : config.c24OwnerPrefixes().keySet()) {
            if (holderLower.contains(token)) {
                return token;
            }
        }
        return null;
    }

    public Map<LocalDate, Map<String, BigDecimal>> aggregateByMonthEnd(
            List<StatementEntry> entries, LocalDate fromInclusive, LocalDate toInclusive) {
        Map<LocalDate, Map<String, BigDecimal>> result = new TreeMap<>();
        for (StatementEntry entry : entries) {
            if (entry.closingDate() == null || entry.closingBalance() == null) {
                continue;
            }
            LocalDate monthEnd = YearMonth.from(entry.closingDate()).atEndOfMonth();
            if (monthEnd.isBefore(fromInclusive) || monthEnd.isAfter(toInclusive)) {
                continue;
            }
            Optional<String> column = mapToOdsColumn(entry);
            if (column.isEmpty()) {
                continue;
            }
            result.computeIfAbsent(monthEnd, d -> new LinkedHashMap<>())
                    .merge(column.get(), entry.closingBalance(), BigDecimal::add);
        }
        return result;
    }

    public List<TransferEvent> listTransferEvents(Path docsRoot) throws IOException {
        List<TransferEvent> events = new ArrayList<>();
        for (Path pdf : listPdfs(docsRoot)) {
            String text;
            try {
                text = pdfTextExtractor.extract(Files.readAllBytes(pdf));
            } catch (RuntimeException ex) {
                continue;
            }
            if (text.contains("TARGOBANK") && text.contains("F I N A N Z S T A T U S")) {
                events.addAll(extractTargobankTransfers(text));
            } else if (text.contains("C24 Bank GmbH")) {
                events.addAll(extractC24Transfers(text));
            }
        }
        events.sort(Comparator.comparing(TransferEvent::date));
        return events;
    }

    /**
     * Computes cumulative month-end balances from counter-account transfer
     * events. Once an account has held a positive balance and drops to
     * {@code <= 0}, {@code 0.00} is emitted explicitly so chart carry-forward does
     * not keep counting the drained balance.
     */
    public Map<LocalDate, Map<String, BigDecimal>> reconstructTransferBalances(
            Path docsRoot, LocalDate fromInclusive, LocalDate toInclusive) throws IOException {
        List<TransferEvent> events = listTransferEvents(docsRoot);
        Map<String, BigDecimal> running = new LinkedHashMap<>();
        Set<String> seenPositive = new HashSet<>();
        Map<LocalDate, Map<String, BigDecimal>> byMonthEnd = new TreeMap<>();
        YearMonth startYm = YearMonth.from(fromInclusive);
        YearMonth endYm = YearMonth.from(toInclusive);

        int idx = 0;
        for (YearMonth ym = startYm; !ym.isAfter(endYm); ym = ym.plusMonths(1)) {
            LocalDate monthEnd = ym.atEndOfMonth();
            while (idx < events.size() && !events.get(idx).date().isAfter(monthEnd)) {
                TransferEvent ev = events.get(idx++);
                BigDecimal updated = running.merge(ev.targetColumn(), ev.delta(), BigDecimal::add);
                if (updated.compareTo(BigDecimal.ZERO) > 0) {
                    seenPositive.add(ev.targetColumn());
                }
            }
            if (!seenPositive.isEmpty()) {
                Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
                for (String col : seenPositive) {
                    BigDecimal bal = running.getOrDefault(col, BigDecimal.ZERO);
                    snapshot.put(col, bal.compareTo(BigDecimal.ZERO) > 0 ? bal : new BigDecimal("0.00"));
                }
                byMonthEnd.put(monthEnd, snapshot);
            }
        }
        return byMonthEnd;
    }

    List<TransferEvent> extractTargobankTransfers(String rawText) {
        String text = StatementText.normalize(rawText);
        Matcher pm = TARGO_PERIOD.matcher(text);
        if (!pm.find()) {
            return List.of();
        }
        LocalDate closingDate = StatementText.parseDate(pm.group(2));
        int year = closingDate.getYear();
        int month = closingDate.getMonthValue();

        List<String> lines = StatementText.lines(text);
        List<TransferEvent> events = new ArrayList<>();
        BigDecimal prevBalance = null;
        LocalDate currentTxDate = null;
        BigDecimal currentSignedDelta = null;
        StringBuilder block = new StringBuilder();

        for (String line : lines) {
            if (line.contains("ANFANGSSALDO")) {
                prevBalance = lastGermanAmount(line);
                continue;
            }
            Matcher tx = TARGO_TX_LINE.matcher(line);
            if (tx.matches()) {
                flushTargobankBlock(events, currentTxDate, currentSignedDelta, block.toString());
                block.setLength(0);
                currentTxDate = parseDayMonth(tx.group(1), year, month);
                currentSignedDelta = null;

                List<BigDecimal> amounts = allGermanAmounts(line);
                if (amounts.size() >= 2) {
                    BigDecimal txAmount = amounts.get(0);
                    BigDecimal newBalance = amounts.get(amounts.size() - 1);
                    boolean isInflow = line.contains("GUTSCHRIFT") || line.contains("LOHN")
                            || line.contains("HABEN") || line.contains("ZINSEN")
                            || (prevBalance != null
                                    && newBalance.subtract(prevBalance).subtract(txAmount).abs()
                                            .compareTo(new BigDecimal("0.01")) <= 0);
                    currentSignedDelta = isInflow ? txAmount.negate() : txAmount;
                    prevBalance = newBalance;
                }
                block.append(line).append('\n');
            } else if (line.contains("ENDSALDO") || line.startsWith("Summe ")) {
                flushTargobankBlock(events, currentTxDate, currentSignedDelta, block.toString());
                block.setLength(0);
                currentTxDate = null;
                currentSignedDelta = null;
                prevBalance = null;
            } else if (currentTxDate != null) {
                block.append(line).append('\n');
            }
        }
        flushTargobankBlock(events, currentTxDate, currentSignedDelta, block.toString());
        return events;
    }

    private void flushTargobankBlock(
            List<TransferEvent> events, LocalDate date, BigDecimal delta, String block) {
        if (date == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        for (String iban : extractIbans(block)) {
            String target = config.transferTrackedIbans().get(iban);
            if (target != null) {
                events.add(new TransferEvent(date, target, delta));
                return;
            }
        }
    }

    private List<TransferEvent> extractC24Transfers(String rawText) {
        String text = StatementText.normalize(rawText);
        Matcher pm = C24_PERIOD.matcher(text);
        if (!pm.find()) {
            return List.of();
        }
        LocalDate closingDate = StatementText.parseDate(pm.group(2));
        String header = text.substring(0, pm.start());
        String headerLower = header.toLowerCase(Locale.ROOT);
        String ownerToken = resolveC24OwnerToken(headerLower);
        boolean isSmartkonto = headerLower.contains("smartkonto");

        int year = closingDate.getYear();
        int month = closingDate.getMonthValue();
        List<String> lines = StatementText.lines(text);
        List<TransferEvent> events = new ArrayList<>();

        LocalDate currentTxDate = null;
        BigDecimal currentDelta = null;
        StringBuilder block = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("Zusammenfassung") || line.startsWith("Startsaldo")) {
                flushC24Block(events, currentTxDate, currentDelta, block.toString(),
                        isSmartkonto ? ownerToken : null);
                break;
            }
            Matcher tx = C24_TX_LINE.matcher(line);
            if (tx.matches()) {
                flushC24Block(events, currentTxDate, currentDelta, block.toString(),
                        isSmartkonto ? ownerToken : null);
                block.setLength(0);
                currentTxDate = parseDayMonth(tx.group(1).replaceAll("\\.$", ""), year, month);
                currentDelta = null;
                block.append(line).append('\n');
            } else if (currentTxDate != null) {
                Matcher amt = C24_SIGNED_AMOUNT.matcher(line);
                if (amt.find()) {
                    BigDecimal val = parseGermanAmount(amt.group(2));
                    currentDelta = "-".equals(amt.group(1)) ? val : val.negate();
                }
                block.append(line).append('\n');
            }
        }
        return events;
    }

    private void flushC24Block(
            List<TransferEvent> events, LocalDate date, BigDecimal delta, String block, String smartkontoOwnerToken) {
        if (date == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        List<String> ibans = extractIbans(block);
        for (String iban : ibans) {
            String target = config.transferTrackedIbans().get(iban);
            if (target != null) {
                events.add(new TransferEvent(date, target, delta));
                return;
            }
        }
        if (smartkontoOwnerToken != null && ibans.isEmpty()) {
            String prefix = smartkontoOwnerToken + ".";
            String blockLower = block.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : config.c24InternalTransfers().entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    String keyword = entry.getKey().substring(prefix.length());
                    if (blockLower.contains(keyword)) {
                        events.add(new TransferEvent(date, entry.getValue(), delta));
                        return;
                    }
                }
            }
        }
    }

    private List<TransferEvent> extractGapC24InternalTransfers(String rawText) {
        if (config.gapInternalTransfers().isEmpty()) {
            return List.of();
        }
        String text = StatementText.normalize(rawText);
        Matcher pm = C24_PERIOD.matcher(text);
        if (!pm.find()) {
            return List.of();
        }
        LocalDate closingDate = StatementText.parseDate(pm.group(2));
        String header = text.substring(0, pm.start()).toLowerCase(Locale.ROOT);
        if (!header.contains("smartkonto")) {
            return List.of();
        }
        String ownerToken = resolveC24OwnerToken(header);
        if (ownerToken == null) {
            return List.of();
        }
        String prefix = ownerToken + ".";
        int year = closingDate.getYear();
        int month = closingDate.getMonthValue();
        List<TransferEvent> events = new ArrayList<>();
        LocalDate currentTxDate = null;
        BigDecimal currentDelta = null;
        StringBuilder block = new StringBuilder();

        for (String line : StatementText.lines(text)) {
            if (line.startsWith("Zusammenfassung") || line.startsWith("Startsaldo")) {
                flushGapInternalBlock(events, currentTxDate, currentDelta, block.toString(), prefix);
                break;
            }
            Matcher tx = C24_TX_LINE.matcher(line);
            if (tx.matches()) {
                flushGapInternalBlock(events, currentTxDate, currentDelta, block.toString(), prefix);
                block.setLength(0);
                currentTxDate = parseDayMonth(tx.group(1).replaceAll("\\.$", ""), year, month);
                currentDelta = null;
                block.append(line).append('\n');
            } else if (currentTxDate != null) {
                Matcher amt = C24_SIGNED_AMOUNT.matcher(line);
                if (amt.find()) {
                    BigDecimal val = parseGermanAmount(amt.group(2));
                    currentDelta = "-".equals(amt.group(1)) ? val : val.negate();
                }
                block.append(line).append('\n');
            }
        }
        return events;
    }

    private void flushGapInternalBlock(
            List<TransferEvent> events, LocalDate date, BigDecimal delta, String block, String prefix) {
        if (date == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0 || !extractIbans(block).isEmpty()) {
            return;
        }
        String blockLower = block.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : config.gapInternalTransfers().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String keyword = entry.getKey().substring(prefix.length());
                if (blockLower.contains(keyword)) {
                    events.add(new TransferEvent(date, entry.getValue(), delta));
                    return;
                }
            }
        }
    }

    public Map<LocalDate, Map<String, BigDecimal>> buildCombinedBackfill(
            Path docsRoot, LocalDate fromInclusive, LocalDate toInclusive) throws IOException {
        ScanResult scan = scanStatements(docsRoot);
        Map<LocalDate, Map<String, BigDecimal>> pdfByMonth = aggregateByMonthEnd(
                scan.entries(), fromInclusive, toInclusive);
        Map<LocalDate, Map<String, BigDecimal>> transfersByMonth = reconstructTransferBalances(
                docsRoot, fromInclusive, toInclusive);

        Map<LocalDate, Map<String, BigDecimal>> combined = new TreeMap<>();
        Set<LocalDate> allDates = new TreeSet<>(pdfByMonth.keySet());
        allDates.addAll(transfersByMonth.keySet());

        for (LocalDate date : allDates) {
            Map<String, BigDecimal> merged = new LinkedHashMap<>();
            Map<String, BigDecimal> tr = transfersByMonth.get(date);
            if (tr != null) {
                merged.putAll(tr);
            }
            Map<String, BigDecimal> pdf = pdfByMonth.get(date);
            if (pdf != null) {
                merged.putAll(pdf);
            }
            combined.put(date, merged);
        }
        return combined;
    }

    public String buildReport(Path docsRoot, Path odsFile, LocalDate fromInclusive, LocalDate toInclusive)
            throws IOException {
        ScanResult scan = scanStatements(docsRoot);
        Map<LocalDate, Map<String, BigDecimal>> combined = buildCombinedBackfill(docsRoot, fromInclusive, toInclusive);

        Map<LocalDate, BigDecimal> odsSumByMonth = new TreeMap<>();
        try (InputStream in = Files.newInputStream(odsFile)) {
            List<OdsAccountColumn> odsCols = FinanceOdsParser.parse(in);
            for (OdsAccountColumn col : odsCols) {
                if (!col.isInclude()) {
                    continue;
                }
                col.values().forEach(v -> {
                    LocalDate d = v.date();
                    if (!d.isBefore(fromInclusive) && !d.isAfter(toInclusive) && v.balance() != null) {
                        odsSumByMonth.merge(d, v.balance(), BigDecimal::add);
                    }
                });
            }
        }

        List<String> columns = config.reportColumns().isEmpty()
                ? collectColumns(combined)
                : config.reportColumns();
        Set<LocalDate> allDates = new TreeSet<>(combined.keySet());
        allDates.addAll(odsSumByMonth.keySet());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "Scanned PDFs: %d unique entries (%d duplicates skipped, %d non-statement/skipped)%n",
                scan.entries().size(), scan.duplicatesSkipped(), scan.skippedFiles().size()));
        sb.append("Date\t").append(String.join("\t", columns)).append("\tReconstructed_Sum\tODS_Sum\tDelta\n");

        for (LocalDate date : allDates) {
            Map<String, BigDecimal> row = combined.getOrDefault(date, Map.of());
            BigDecimal recSum = row.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal odsSum = odsSumByMonth.getOrDefault(date, BigDecimal.ZERO);
            BigDecimal delta = recSum.subtract(odsSum);

            sb.append(date);
            for (String col : columns) {
                BigDecimal val = row.get(col);
                sb.append('\t').append(val == null ? "" : val.toPlainString());
            }
            sb.append('\t').append(recSum.toPlainString());
            sb.append('\t').append(odsSum.toPlainString());
            sb.append('\t').append(delta.toPlainString());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static List<String> collectColumns(Map<LocalDate, Map<String, BigDecimal>> combined) {
        Set<String> cols = new TreeSet<>();
        combined.values().forEach(m -> cols.addAll(m.keySet()));
        return new ArrayList<>(cols);
    }

    public void writeBackfilledOds(
            Path sourceOds, Path targetOds, Map<LocalDate, Map<String, BigDecimal>> rowsToInsert)
            throws IOException {
        Map<String, Integer> colIndexMap = mapOdsColumnIndices(sourceOds);

        byte[] contentXmlBytes = null;
        Map<String, byte[]> otherEntries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(sourceOds))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] bytes = zis.readAllBytes();
                if ("content.xml".equals(entry.getName())) {
                    contentXmlBytes = bytes;
                } else {
                    otherEntries.put(entry.getName(), bytes);
                }
            }
        }
        if (contentXmlBytes == null) {
            throw new IllegalArgumentException("content.xml not found in " + sourceOds);
        }

        byte[] updatedXml = insertRowsIntoContentXml(contentXmlBytes, colIndexMap, rowsToInsert);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetOds))) {
            if (otherEntries.containsKey("mimetype")) {
                ZipEntry mime = new ZipEntry("mimetype");
                mime.setMethod(ZipEntry.STORED);
                byte[] mimeBytes = otherEntries.remove("mimetype");
                mime.setSize(mimeBytes.length);
                mime.setCompressedSize(mimeBytes.length);
                CRC32 crc = new CRC32();
                crc.update(mimeBytes);
                mime.setCrc(crc.getValue());
                zos.putNextEntry(mime);
                zos.write(mimeBytes);
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("content.xml"));
            zos.write(updatedXml);
            zos.closeEntry();
            for (Map.Entry<String, byte[]> e : otherEntries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }

    private static Map<String, Integer> mapOdsColumnIndices(Path sourceOds) throws IOException {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(sourceOds)) {
            for (OdsAccountColumn col : FinanceOdsParser.parse(in)) {
                if ("mtl. Kontostände".equals(col.sheetName())) {
                    String inst = col.institutionHeader().trim();
                    String sub = col.subHeader().trim();
                    map.put((inst + " / " + sub).trim(), col.columnIndex());
                }
            }
        }
        return map;
    }

    private static byte[] insertRowsIntoContentXml(
            byte[] xmlBytes, Map<String, Integer> colIndexMap, Map<LocalDate, Map<String, BigDecimal>> rowsToInsert) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(xmlBytes));

            Element matrixTable = findTableByName(doc, "mtl. Kontostände");
            if (matrixTable == null) {
                throw new IllegalArgumentException("Sheet 'mtl. Kontostände' not found");
            }

            List<Element> tableRows = new ArrayList<>();
            NodeList children = matrixTable.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n instanceof Element el && "table-row".equals(el.getLocalName())) {
                    tableRows.add(el);
                }
            }

            Element firstOlderRow = null;
            Element lastDataRow = null;
            Set<LocalDate> existingDates = new TreeSet<>();
            for (Element row : tableRows) {
                LocalDate d = extractRowDate(row);
                if (d != null) {
                    existingDates.add(d);
                    lastDataRow = row;
                }
            }
            if (lastDataRow == null) {
                throw new IllegalArgumentException("No date rows found in 'mtl. Kontostände'");
            }

            List<LocalDate> descending = rowsToInsert.keySet().stream()
                    .filter(d -> !existingDates.contains(d))
                    .sorted(Comparator.reverseOrder())
                    .toList();

            int maxCol = colIndexMap.values().stream().mapToInt(Integer::intValue).max().orElse(26);
            for (LocalDate date : descending) {
                Map<String, BigDecimal> values = rowsToInsert.get(date);
                if (values == null || values.isEmpty()) {
                    continue;
                }
                firstOlderRow = findFirstRowOlderThan(matrixTable, date);
                Node insertBefore = firstOlderRow != null ? firstOlderRow : lastDataRow.getNextSibling();
                Element newRow = buildOdsRow(doc, lastDataRow, date, values, colIndexMap, maxCol);
                matrixTable.insertBefore(newRow, insertBefore);
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to update content.xml: " + ex.getMessage(), ex);
        }
    }

    private static Element buildOdsRow(
            Document doc,
            Element templateRow,
            LocalDate date,
            Map<String, BigDecimal> values,
            Map<String, Integer> colIndexMap,
            int maxCol) {
        Element row = doc.createElementNS(TABLE_NS, "table:table-row");
        String styleName = templateRow.getAttributeNS(TABLE_NS, "style-name");
        if (!styleName.isEmpty()) {
            row.setAttributeNS(TABLE_NS, "table:style-name", styleName);
        }

        Map<Integer, BigDecimal> byColIdx = new LinkedHashMap<>();
        values.forEach((key, val) -> {
            Integer idx = colIndexMap.get(key.trim());
            if (idx != null && val != null) {
                byColIdx.put(idx, val);
            }
        });
        BigDecimal total = byColIdx.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        for (int col = 0; col <= maxCol; col++) {
            Element cell = doc.createElementNS(TABLE_NS, "table:table-cell");
            if (col == 1) {
                cell.setAttributeNS(OFFICE_NS, "office:value-type", "date");
                cell.setAttributeNS(OFFICE_NS, "office:date-value", date.toString());
                Element p = doc.createElementNS(TEXT_NS, "text:p");
                p.setTextContent(String.format(Locale.ROOT, "%02d.%02d.%04d",
                        date.getDayOfMonth(), date.getMonthValue(), date.getYear()));
                cell.appendChild(p);
            } else if (col == 2) {
                cell.setAttributeNS(OFFICE_NS, "office:value-type", "float");
                cell.setAttributeNS(OFFICE_NS, "office:value", total.toPlainString());
                Element p = doc.createElementNS(TEXT_NS, "text:p");
                p.setTextContent(total.toPlainString());
                cell.appendChild(p);
            } else if (byColIdx.containsKey(col)) {
                BigDecimal v = byColIdx.get(col);
                cell.setAttributeNS(OFFICE_NS, "office:value-type", "float");
                cell.setAttributeNS(OFFICE_NS, "office:value", v.toPlainString());
                Element p = doc.createElementNS(TEXT_NS, "text:p");
                p.setTextContent(v.toPlainString());
                cell.appendChild(p);
            }
            row.appendChild(cell);
        }
        return row;
    }

    private static Element findFirstRowOlderThan(Element matrixTable, LocalDate targetDate) {
        NodeList children = matrixTable.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element row && "table-row".equals(row.getLocalName())) {
                LocalDate d = extractRowDate(row);
                if (d != null && d.isBefore(targetDate)) {
                    return row;
                }
            }
        }
        return null;
    }

    private static Element findTableByName(Document doc, String name) {
        NodeList tables = doc.getElementsByTagNameNS(TABLE_NS, "table");
        for (int i = 0; i < tables.getLength(); i++) {
            Element el = (Element) tables.item(i);
            if (name.equals(el.getAttributeNS(TABLE_NS, "name"))) {
                return el;
            }
        }
        return null;
    }

    private static LocalDate extractRowDate(Element row) {
        NodeList cells = row.getElementsByTagNameNS(TABLE_NS, "table-cell");
        int col = 0;
        for (int i = 0; i < cells.getLength(); i++) {
            Element cell = (Element) cells.item(i);
            String rep = cell.getAttributeNS(TABLE_NS, "number-columns-repeated");
            int repeat = rep.isEmpty() ? 1 : Integer.parseInt(rep);
            if (col <= 1 && col + repeat > 1) {
                String dv = cell.getAttributeNS(OFFICE_NS, "date-value");
                if (!dv.isEmpty()) {
                    return LocalDate.parse(dv.substring(0, 10));
                }
            }
            col += repeat;
            if (col > 2) {
                break;
            }
        }
        return null;
    }

    private static List<Path> listPdfs(Path docsRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(docsRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
    }

    private static LocalDate parseDayMonth(String ddMm, int year, int statementMonth) {
        String[] parts = ddMm.split("\\.");
        int day = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int resolvedYear = (month > statementMonth && statementMonth <= 2) ? year - 1 : year;
        return LocalDate.of(resolvedYear, month, day);
    }

    private static List<BigDecimal> allGermanAmounts(String line) {
        List<BigDecimal> list = new ArrayList<>();
        Matcher m = GERMAN_AMOUNT.matcher(line);
        while (m.find()) {
            list.add(parseGermanAmount(m.group(1)));
        }
        return list;
    }

    private static BigDecimal lastGermanAmount(String line) {
        List<BigDecimal> all = allGermanAmounts(line);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static BigDecimal parseGermanAmount(String raw) {
        return new BigDecimal(raw.replace(".", "").replace(",", "."));
    }

    private static List<String> extractIbans(String text) {
        List<String> result = new ArrayList<>();
        Matcher m = IBAN_PATTERN.matcher(text);
        while (m.find()) {
            String normalized = m.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (normalized.length() >= 18) {
                result.add(normalized);
            }
        }
        return result;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: OdsStatementBackfillHelper <docsRoot> <odsFile> "
                    + "[--config <file>] [--from YYYY-MM-DD] [--to YYYY-MM-DD] [--out <targetOds>] [--verbose]");
            System.exit(1);
        }
        Path docsRoot = Path.of(args[0]);
        Path odsFile = Path.of(args[1]);
        Path configPath = resolveDefaultConfig(docsRoot, odsFile);
        LocalDate from = LocalDate.of(2022, 9, 30);
        LocalDate to = LocalDate.of(2024, 7, 31);
        Path targetOds = null;
        boolean verbose = false;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[++i]);
                case "--from" -> from = LocalDate.parse(args[++i]);
                case "--to" -> to = LocalDate.parse(args[++i]);
                case "--out" -> targetOds = Path.of(args[++i]);
                case "--verbose" -> verbose = true;
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        BackfillConfig config = BackfillConfig.load(configPath);
        OdsStatementBackfillHelper helper = new OdsStatementBackfillHelper(config);
        if (verbose) {
            helper.listTransferEvents(docsRoot).forEach(System.out::println);
        }
        System.out.print(helper.buildReport(docsRoot, odsFile, from, to));

        if (targetOds != null) {
            LocalDate backfillEnd = to.isBefore(LocalDate.of(2024, 6, 30)) ? to : LocalDate.of(2024, 6, 30);
            Map<LocalDate, Map<String, BigDecimal>> rows = new TreeMap<>(
                    helper.buildCombinedBackfill(docsRoot, from, backfillEnd));
            if (!config.gapFillDates().isEmpty()) {
                rows.putAll(helper.buildGapFillRows(docsRoot, odsFile));
            }
            helper.writeBackfilledOds(odsFile, targetOds, rows);
            System.out.println("Wrote backfilled ODS to: " + targetOds);
        }
    }

    /**
     * Builds rows for configured {@code gapFillDates} (e.g. a month-end date
     * present in the Targobank sheet between two matrix dates) by carrying
     * forward the latest matrix row strictly before the gap date, applying
     * intermediate IBAN/internal transfers, and overlaying exact PDF closing
     * balances for that month.
     */
    public Map<LocalDate, Map<String, BigDecimal>> buildGapFillRows(Path docsRoot, Path odsFile) throws IOException {
        if (config.gapFillDates().isEmpty()) {
            return Map.of();
        }
        TreeMap<LocalDate, Map<String, BigDecimal>> matrixByDate = readMatrixRows(odsFile);
        ScanResult scan = scanStatements(docsRoot);
        List<TransferEvent> externalTransfers = listTransferEvents(docsRoot);
        List<TransferEvent> gapInternals = new ArrayList<>();
        for (Path pdf : listPdfs(docsRoot)) {
            String text;
            try {
                text = pdfTextExtractor.extract(Files.readAllBytes(pdf));
            } catch (RuntimeException ex) {
                continue;
            }
            if (text.contains("C24 Bank GmbH")) {
                gapInternals.addAll(extractGapC24InternalTransfers(text));
            }
        }

        Map<LocalDate, Map<String, BigDecimal>> result = new TreeMap<>();
        for (LocalDate gapDate : config.gapFillDates()) {
            Map.Entry<LocalDate, Map<String, BigDecimal>> baselineEntry = matrixByDate.lowerEntry(gapDate);
            if (baselineEntry == null) {
                continue;
            }
            LocalDate baselineDate = baselineEntry.getKey();
            Map<String, BigDecimal> row = new LinkedHashMap<>(baselineEntry.getValue());

            for (TransferEvent ev : externalTransfers) {
                if (ev.date().isAfter(baselineDate) && !ev.date().isAfter(gapDate)
                        && config.transferTrackedIbans().containsValue(ev.targetColumn())) {
                    BigDecimal next = row.getOrDefault(ev.targetColumn(), BigDecimal.ZERO).add(ev.delta());
                    row.put(ev.targetColumn(), next.compareTo(BigDecimal.ZERO) > 0 ? next : new BigDecimal("0.00"));
                }
            }
            for (TransferEvent ev : gapInternals) {
                if (ev.date().isAfter(baselineDate) && !ev.date().isAfter(gapDate)) {
                    BigDecimal next = row.getOrDefault(ev.targetColumn(), BigDecimal.ZERO).add(ev.delta());
                    row.put(ev.targetColumn(), next.compareTo(BigDecimal.ZERO) > 0 ? next : new BigDecimal("0.00"));
                }
            }
            Map<LocalDate, Map<String, BigDecimal>> pdfAtMonth = aggregateByMonthEnd(
                    scan.entries(), gapDate.withDayOfMonth(1), gapDate);
            Map<String, BigDecimal> pdfValues = pdfAtMonth.get(gapDate);
            if (pdfValues != null) {
                row.putAll(pdfValues);
            }
            result.put(gapDate, row);
        }
        return result;
    }

    private static TreeMap<LocalDate, Map<String, BigDecimal>> readMatrixRows(Path odsFile) throws IOException {
        TreeMap<LocalDate, Map<String, BigDecimal>> byDate = new TreeMap<>();
        try (InputStream in = Files.newInputStream(odsFile)) {
            for (OdsAccountColumn col : FinanceOdsParser.parse(in)) {
                if (!"mtl. Kontostände".equals(col.sheetName())) {
                    continue;
                }
                String key = (col.institutionHeader().trim() + " / " + col.subHeader().trim()).trim();
                for (var sv : col.values()) {
                    if (sv.date() != null && sv.balance() != null) {
                        byDate.computeIfAbsent(sv.date(), d -> new LinkedHashMap<>()).put(key, sv.balance());
                    }
                }
            }
        }
        return byDate;
    }

    private static Path resolveDefaultConfig(Path docsRoot, Path odsFile) {
        Path inDocs = docsRoot.resolve(DEFAULT_CONFIG_FILENAME);
        if (Files.isRegularFile(inDocs)) {
            return inDocs;
        }
        if (docsRoot.getParent() != null) {
            Path inParent = docsRoot.getParent().resolve(DEFAULT_CONFIG_FILENAME);
            if (Files.isRegularFile(inParent)) {
                return inParent;
            }
        }
        if (odsFile.getParent() != null) {
            Path nextToOds = odsFile.getParent().resolve(DEFAULT_CONFIG_FILENAME);
            if (Files.isRegularFile(nextToOds)) {
                return nextToOds;
            }
        }
        throw new IllegalArgumentException(
                "Missing " + DEFAULT_CONFIG_FILENAME + "; pass --config <path-to-properties>");
    }

    private record EntryKey(String importKey, LocalDate closingDate) {
    }

    record TransferEvent(LocalDate date, String targetColumn, BigDecimal delta) {
    }

    public record ScanResult(List<StatementEntry> entries, int duplicatesSkipped, List<String> skippedFiles) {
    }

    /**
     * Externalized account/IBAN mapping configuration so no personal identifiers
     * are stored in source code.
     */
    public record BackfillConfig(
            Map<String, String> ibanToOdsColumn,
            Map<String, String> accountNumberToOdsColumn,
            Map<String, String> transferTrackedIbans,
            Map<String, String> bankDefaultColumns,
            Map<String, String> c24OwnerPrefixes,
            Map<String, String> c24LabelOverrides,
            Map<String, String> c24InternalTransfers,
            List<String> reportColumns,
            List<LocalDate> gapFillDates,
            Map<String, String> gapInternalTransfers) {

        public BackfillConfig(
                Map<String, String> ibanToOdsColumn,
                Map<String, String> accountNumberToOdsColumn,
                Map<String, String> transferTrackedIbans,
                Map<String, String> bankDefaultColumns,
                Map<String, String> c24OwnerPrefixes,
                Map<String, String> c24LabelOverrides,
                Map<String, String> c24InternalTransfers,
                List<String> reportColumns) {
            this(ibanToOdsColumn, accountNumberToOdsColumn, transferTrackedIbans, bankDefaultColumns,
                    c24OwnerPrefixes, c24LabelOverrides, c24InternalTransfers, reportColumns,
                    List.of(), Map.of());
        }

        public static BackfillConfig load(Path path) throws IOException {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            return fromProperties(props);
        }

        public static BackfillConfig fromProperties(Properties props) {
            Map<String, String> ibans = new LinkedHashMap<>();
            Map<String, String> numbers = new LinkedHashMap<>();
            Map<String, String> tracked = new LinkedHashMap<>();
            Map<String, String> bankDefaults = new LinkedHashMap<>();
            Map<String, String> owners = new LinkedHashMap<>();
            Map<String, String> labelOverrides = new LinkedHashMap<>();
            Map<String, String> internalTransfers = new LinkedHashMap<>();
            List<String> reportCols = List.of();
            List<LocalDate> gapDates = List.of();
            Map<String, String> gapInternals = new LinkedHashMap<>();

            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (value != null) {
                    value = value.trim();
                }
                if (key.startsWith("iban.")) {
                    ibans.put(key.substring("iban.".length()).replaceAll("\\s+", "").toUpperCase(Locale.ROOT), value);
                } else if (key.startsWith("accountNumber.")) {
                    numbers.put(key.substring("accountNumber.".length()).trim(), value);
                } else if (key.startsWith("transferTracked.")) {
                    String iban = key.substring("transferTracked.".length())
                            .replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                    tracked.put(iban, value);
                    ibans.putIfAbsent(iban, value);
                } else if (key.startsWith("bankDefault.")) {
                    bankDefaults.put(key.substring("bankDefault.".length()).toLowerCase(Locale.ROOT), value);
                } else if (key.startsWith("c24Owner.")) {
                    owners.put(key.substring("c24Owner.".length()).toLowerCase(Locale.ROOT), value);
                } else if (key.startsWith("c24LabelOverride.")) {
                    labelOverrides.put(key.substring("c24LabelOverride.".length()).toLowerCase(Locale.ROOT), value);
                } else if (key.startsWith("c24InternalTransfer.")) {
                    internalTransfers.put(
                            key.substring("c24InternalTransfer.".length()).toLowerCase(Locale.ROOT), value);
                } else if (key.startsWith("gapInternalTransfer.")) {
                    gapInternals.put(
                            key.substring("gapInternalTransfer.".length()).toLowerCase(Locale.ROOT), value);
                } else if ("reportColumns".equals(key) && value != null && !value.isBlank()) {
                    reportCols = Arrays.stream(value.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
                } else if ("gapFillDates".equals(key) && value != null && !value.isBlank()) {
                    gapDates = Arrays.stream(value.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(LocalDate::parse)
                            .toList();
                }
            }
            return new BackfillConfig(
                    Map.copyOf(ibans),
                    Map.copyOf(numbers),
                    Map.copyOf(tracked),
                    Map.copyOf(bankDefaults),
                    Map.copyOf(owners),
                    Map.copyOf(labelOverrides),
                    Map.copyOf(internalTransfers),
                    reportCols,
                    gapDates,
                    Map.copyOf(gapInternals));
        }
    }
}
