package de.in.mp.worktime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import de.in.mp.worktime.WorkTimeSummary.DaySummary;
import de.in.mp.worktime.WorkTimeSummary.PeriodSummary;

/**
 * Generates a formal German overload notice (Überlastungsanzeige) PDF
 * document with dynamic contract/period metrics and a complete chronological
 * work-time table using Apache PDFBox.
 */
@Component
public class OverloadNoticePdfGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final float MM_TO_PT = 72f / 25.4f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    /** DIN 5008 Form B: 25 mm left margin, 20 mm right/bottom margin, 22 mm top header margin. */
    private static final float MARGIN_LEFT = 25f * MM_TO_PT;
    private static final float MARGIN_RIGHT = 20f * MM_TO_PT;
    private static final float MARGIN_TOP = 22f * MM_TO_PT;
    private static final float MARGIN_BOTTOM = 20f * MM_TO_PT;
    private static final float ADDRESS_TOP_Y = PAGE_HEIGHT - (45f * MM_TO_PT);
    private static final float CONTENT_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;

    private static final PDType1Font FONT_REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font FONT_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    /**
     * Customizable header/letter options for the generated PDF.
     */
    public record OverloadNoticeOptions(
            String senderName,
            String schoolName,
            String recipientText,
            String copyToText,
            String customNote,
            LocalDate letterDate) {
    }

    public byte[] generate(WorkTimeProfile profile, PeriodSummary summary, OverloadNoticeOptions options) {
        if (profile == null || summary == null) {
            throw new IllegalArgumentException("Work time profile and summary are required");
        }
        OverloadNoticeOptions opts = options != null
                ? options
                : new OverloadNoticeOptions("", "", "", "", "", LocalDate.now());
        LocalDate letterDate = opts.letterDate() != null ? opts.letterDate() : LocalDate.now();

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(doc);
            writeLetterPage(writer, profile, summary, opts, letterDate);
            writeTimeTable(writer, profile, summary);
            writer.finish();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to generate overload notice PDF: " + exception.getMessage(),
                    exception);
        }
    }

    private void writeLetterPage(
            PdfWriter writer,
            WorkTimeProfile profile,
            PeriodSummary summary,
            OverloadNoticeOptions opts,
            LocalDate letterDate) throws IOException {
        boolean hasSenderHeader = !isBlank(opts.senderName()) || !isBlank(opts.schoolName());
        if (hasSenderHeader) {
            if (!isBlank(opts.senderName())) {
                writer.writeParagraph(opts.senderName(), FONT_BOLD, 11f, 14.5f);
            }
            if (!isBlank(opts.schoolName())) {
                writer.writeParagraph(opts.schoolName(), FONT_REGULAR, 9.5f, 13f);
            }
            writer.advance(4f);
            writer.drawHorizontalLine(0.4f);
        }

        // DIN 5008 Form B: recipient address zone starts at 45 mm from top edge
        if (writer.currentY() > ADDRESS_TOP_Y) {
            writer.setY(ADDRESS_TOP_Y);
        } else {
            writer.advance(14f);
        }

        if (hasSenderHeader) {
            List<String> senderParts = new ArrayList<>();
            if (!isBlank(opts.senderName())) {
                senderParts.add(opts.senderName().trim());
            }
            if (!isBlank(opts.schoolName())) {
                senderParts.add(opts.schoolName().trim());
            }
            writer.writeParagraph(String.join(" - ", senderParts), FONT_REGULAR, 7.5f, 10f);
            writer.advance(4f);
        }

        String recipient = !isBlank(opts.recipientText())
                ? opts.recipientText()
                : "An die Schulleitung";
        for (String line : recipient.split("\\R")) {
            writer.writeParagraph(line, FONT_REGULAR, 10f, 13.5f);
        }
        if (!isBlank(opts.copyToText())) {
            writer.advance(3f);
            writer.writeParagraph("In Kopie an: " + opts.copyToText(), FONT_REGULAR, 9f, 12.5f);
        }
        writer.advance(10f);

        writer.writeRightAligned("Datum: " + DATE_FMT.format(letterDate), FONT_REGULAR, 10f);
        writer.advance(26f);

        writer.writeParagraph(
                "Überlastungsanzeige und Anzeige erheblicher Arbeitszeitüberschreitungen",
                FONT_BOLD, 12f, 16f);
        writer.advance(24f);

        writer.writeParagraph("Sehr geehrte Damen und Herren,", FONT_REGULAR, 10f, 14f);
        writer.advance(14f);

        String pct = profile.getPartTimeRatio()
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .toPlainString();
        String contractUnits = stripZeros(profile.getContractTeachingUnits());
        String fullUnits = stripZeros(profile.getFullTimeTeachingUnits());
        String paidWeekly = WorkTimeService.formatMinutes(profile.getPaidWeeklyMinutes());
        String paidDaily = WorkTimeService.formatMinutes(profile.getPaidDailyMinutes());

        String periodStr = DATE_FMT.format(summary.from()) + " bis " + DATE_FMT.format(summary.to());
        String totalWork = WorkTimeService.formatMinutes(summary.totalWorkMinutes());
        String targetWork = WorkTimeService.formatMinutes(summary.targetMinutes());
        String overtime = WorkTimeService.formatSignedMinutes(summary.overtimeMinutes());

        writer.writeParagraph(
                "mit diesem Schreiben zeige ich im Sinne der gesetzlichen Fürsorgepflicht sowie zu meiner "
                        + "eigenen gesundheitlichen und haftungsrechtlichen Entlastung eine anhaltende "
                        + "arbeitszeitliche Überlastung an.",
                FONT_REGULAR, 9.5f, 13.5f);
        writer.advance(7f);

        writer.writeParagraph(
                "Mein aktueller Beschäftigungsumfang beträgt " + contractUnits + " von " + fullUnits
                        + " Unterrichtsstunden (" + pct + " %). Bezogen auf eine reguläre Vollzeit-Arbeitszeit von "
                        + stripZeros(profile.getFullTimeWeeklyHours()) + " Stunden entspricht dies einer "
                        + "bezahlten Soll-Arbeitszeit von " + paidWeekly + " pro Woche (" + paidDaily
                        + " pro Regelarbeitstag).",
                FONT_REGULAR, 9.5f, 13.5f);
        writer.advance(7f);

        writer.writeParagraph(
                "Im dokumentierten Zeitraum vom " + periodStr + " (siehe Anlage) betrug meine tatsächlich "
                        + "erforderliche und geleistete Arbeitszeit insgesamt " + totalWork
                        + " gegenüber einem bezahlten Vertragssoll von " + targetWork
                        + " (Mehrarbeitssaldo: " + overtime + ").",
                FONT_REGULAR, 9.5f, 13.5f);
        writer.advance(8f);

        writer.writeParagraph("Dabei traten folgende kritische Belastungen auf:", FONT_BOLD, 9.5f, 13.5f);
        writer.advance(3f);
        writer.writeBullet(
                "Aufteilung der Arbeitszeit: Schule (Präsenz) "
                        + WorkTimeService.formatMinutes(summary.schoolMinutes())
                        + ", Homeoffice/Vor- und Nachbereitung "
                        + WorkTimeService.formatMinutes(summary.homeOfficeMinutes())
                        + ", Konferenzen/Gespräche "
                        + WorkTimeService.formatMinutes(summary.meetingMinutes()) + ".",
                9.2f, 12.5f);
        if (summary.daysOverDailyLimit() > 0 || summary.shortRestCount() > 0) {
            List<String> limitParts = new ArrayList<>();
            if (summary.daysOverDailyLimit() > 0) {
                String dayWord = summary.daysOverDailyLimit() == 1 ? "Tag" : "Tage";
                limitParts.add(summary.daysOverDailyLimit() + " " + dayWord + " mit mehr als "
                        + stripZeros(profile.getDailyWarningHours())
                        + " Stunden Arbeitszeit (Überschreitung der zulässigen Höchstarbeitszeit nach ArbZG)");
            }
            if (summary.shortRestCount() > 0) {
                String restWord = summary.shortRestCount() == 1 ? "Unterschreitung" : "Unterschreitungen";
                limitParts.add(summary.shortRestCount() + " " + restWord
                        + " der gesetzlichen Mindestruhezeit von "
                        + stripZeros(profile.getMinRestHours()) + " Stunden zwischen zwei Arbeitstagen");
            }
            writer.writeBullet(
                    "Tageshöhe und Ruhezeiten: " + String.join(" sowie ", limitParts) + ".",
                    9.2f, 12.5f);
        }
        if (summary.eveningMinutes() > 0 || summary.weekendMinutes() > 0) {
            List<String> offParts = new ArrayList<>();
            if (summary.eveningMinutes() > 0) {
                offParts.add(WorkTimeService.formatMinutes(summary.eveningMinutes()) + " ab 18:00 Uhr");
            }
            if (summary.weekendMinutes() > 0) {
                offParts.add(WorkTimeService.formatMinutes(summary.weekendMinutes())
                        + " am Wochenende (Beeinträchtigung der ununterbrochenen Wochenruhezeit)");
            }
            writer.writeBullet(
                    "Arbeit außerhalb der Kernzeit: " + String.join(" sowie ", offParts) + ".",
                    9.2f, 12.5f);
        }
        writer.writeBullet(
                "Pausenregelung (§ 4 ArbZG): Durch Raumwechsel, Unterrichtsvorbereitung, Schülergespräche "
                        + "und Aufsichtspflichten konnten gesetzliche Ruhepausen während langer Präsenztage "
                        + "teilweise nicht genommen werden.",
                9.2f, 12.5f);
        writer.advance(6f);

        if (!isBlank(opts.customNote())) {
            writer.writeParagraph(opts.customNote().trim(), FONT_REGULAR, 9.5f, 13.5f);
            writer.advance(7f);
        }

        writer.writeParagraph(
                "Ich bitte daher um kurzfristige Prüfung und Abhilfe, insbesondere durch eine dem "
                        + "Teilzeitanteil entsprechende Reduzierung außerunterrichtlicher Aufgaben, "
                        + "Konferenzen und Vertretungen sowie die Gewährleistung gesetzlicher Pausen- und Ruhezeiten. "
                        + "Für ein klärendes Gespräch stehe ich gerne zur Verfügung.",
                FONT_REGULAR, 9.5f, 13.5f);
        writer.advance(18f);

        writer.ensureSpace(72f, null);
        writer.writeParagraph("Mit freundlichen Grüßen", FONT_REGULAR, 10f, 14f);
        writer.advance(34f);
        String sig = !isBlank(opts.senderName()) ? opts.senderName() : "Unterschrift";
        writer.drawSignatureLine(190f);
        writer.advance(11f);
        writer.writeParagraph(sig, FONT_REGULAR, 9.5f, 13f);
    }

    private void writeTimeTable(PdfWriter writer, WorkTimeProfile profile, PeriodSummary summary) throws IOException {
        writer.newPage();
        writer.writeParagraph(
                "Anlage: Dokumentierte Arbeitszeiten ("
                        + DATE_FMT.format(summary.from()) + " bis " + DATE_FMT.format(summary.to()) + ")",
                FONT_BOLD, 11.5f, 16f);
        writer.advance(6f);

        float[] colX = {
            MARGIN_LEFT,
            MARGIN_LEFT + 56f,
            MARGIN_LEFT + 80f,
            MARGIN_LEFT + 128f,
            MARGIN_LEFT + 172f,
            MARGIN_LEFT + 222f,
            MARGIN_LEFT + 276f
        };
        String[] headers = { "Datum", "Tag", "Arbeitszeit", "Soll", "Saldo", "Hinweise", "Blöcke & Tätigkeiten" };
        writer.writeTableHeader(colX, headers);

        List<DaySummary> chronological = new ArrayList<>(summary.days());
        chronological.sort((a, b) -> a.date().compareTo(b.date()));

        for (DaySummary day : chronological) {
            String dateStr = DATE_FMT.format(day.date());
            String weekday = day.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.GERMAN);
            String workStr = WorkTimeService.formatMinutes(day.workMinutes());
            String targetStr = WorkTimeService.formatMinutes(day.targetMinutes());
            String deltaStr = WorkTimeService.formatSignedMinutes(day.overtimeMinutes());
            String hints = buildHints(day, profile);
            String blocks = formatBlocks(day);

            List<String> wrappedBlocks = wrapText(blocks, FONT_REGULAR, 8f, PAGE_WIDTH - MARGIN_RIGHT - colX[6]);
            if (wrappedBlocks.isEmpty()) {
                wrappedBlocks = List.of("-");
            }
            float rowHeight = wrappedBlocks.size() * 10.5f + 4f;
            writer.ensureSpace(rowHeight + 8f, () -> writer.writeTableHeader(colX, headers));

            float startY = writer.currentY();
            writer.drawTextAt(colX[0], startY, dateStr, FONT_REGULAR, 8f);
            writer.drawTextAt(colX[1], startY, weekday, FONT_REGULAR, 8f);
            writer.drawTextAt(colX[2], startY, workStr, FONT_BOLD, 8f);
            writer.drawTextAt(colX[3], startY, targetStr, FONT_REGULAR, 8f);
            writer.drawTextAt(colX[4], startY, deltaStr, FONT_REGULAR, 8f);
            writer.drawTextAt(colX[5], startY, hints, FONT_REGULAR, 8f);

            float blockY = startY;
            for (String line : wrappedBlocks) {
                writer.drawTextAt(colX[6], blockY, line, FONT_REGULAR, 8f);
                blockY -= 10.5f;
            }
            writer.setY(startY - rowHeight);
        }

        writer.ensureSpace(24f, null);
        writer.drawHorizontalLine();
        writer.advance(12f);
        float sumY = writer.currentY();
        writer.drawTextAt(colX[0], sumY, "Gesamt", FONT_BOLD, 8.5f);
        writer.drawTextAt(colX[2], sumY, WorkTimeService.formatMinutes(summary.totalWorkMinutes()), FONT_BOLD, 8.5f);
        writer.drawTextAt(colX[3], sumY, WorkTimeService.formatMinutes(summary.targetMinutes()), FONT_BOLD, 8.5f);
        writer.drawTextAt(colX[4], sumY, WorkTimeService.formatSignedMinutes(summary.overtimeMinutes()), FONT_BOLD, 8.5f);
    }

    private static String buildHints(DaySummary day, WorkTimeProfile profile) {
        List<String> parts = new ArrayList<>();
        if (day.weekend()) {
            parts.add("WE");
        }
        if (day.overDailyLimit()) {
            parts.add(">" + stripZeros(profile.getDailyWarningHours()) + "h");
        }
        if (day.shortRestViolation()) {
            parts.add("<" + stripZeros(profile.getMinRestHours()) + "h Ruhe");
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    private static String formatBlocks(DaySummary day) {
        return day.entries().stream()
                .map(e -> {
                    String cat = switch (e.getCategory()) {
                        case SCHOOL -> "Schule";
                        case HOME_OFFICE -> "Home";
                        case MEETING -> "Konf.";
                        case OTHER -> "Sonst.";
                    };
                    String base = TIME_FMT.format(e.getStartTime()) + "-" + TIME_FMT.format(e.getEndTime())
                            + " (" + cat + ")";
                    if (e.getNote() != null && !e.getNote().isBlank()) {
                        base += ": " + e.getNote().trim();
                    }
                    return base;
                })
                .collect(Collectors.joining("; "));
    }

    private static List<String> wrapText(String text, PDType1Font font, float fontSize, float maxWidth)
            throws IOException {
        List<String> lines = new ArrayList<>();
        String sanitized = sanitizeForWinAnsi(text);
        if (sanitized.isBlank()) {
            return lines;
        }
        String[] words = sanitized.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            float width = font.getStringWidth(candidate) / 1000f * fontSize;
            if (width <= maxWidth) {
                current = new StringBuilder(candidate);
            } else {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                }
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    static String sanitizeForWinAnsi(String raw) {
        if (raw == null) {
            return "";
        }
        String pre = raw
                .replace("\u2265", ">=")
                .replace("\u2192", "->")
                .replace("\u2212", "-")
                .replace("\u2013", "-")
                .replace("\u2014", "-")
                .replace("\u00B7", "-");
        StringBuilder sb = new StringBuilder(pre.length());
        for (int i = 0; i < pre.length(); i++) {
            char c = pre.charAt(i);
            if ((c >= 32 && c <= 126) || (c >= 160 && c <= 255) || c == '\u20AC') {
                sb.append(c);
            } else if (c == '\t') {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static String stripZeros(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @FunctionalInterface
    private interface PageHeaderCallback {
        void drawHeader() throws IOException;
    }

    private static final class PdfWriter {

        private final PDDocument doc;
        private PDPageContentStream stream;
        private float y;

        PdfWriter(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            y = PAGE_HEIGHT - MARGIN_TOP;
        }

        float currentY() {
            return y;
        }

        void setY(float nextY) {
            this.y = nextY;
        }

        void advance(float delta) {
            this.y -= delta;
        }

        void ensureSpace(float needed, PageHeaderCallback onNewPage) throws IOException {
            if (y - needed < MARGIN_BOTTOM) {
                newPage();
                if (onNewPage != null) {
                    onNewPage.drawHeader();
                }
            }
        }

        void writeParagraph(String text, PDType1Font font, float fontSize, float lineHeight) throws IOException {
            List<String> lines = wrapText(text, font, fontSize, CONTENT_WIDTH);
            if (lines.isEmpty()) {
                return;
            }
            for (String line : lines) {
                ensureSpace(lineHeight, null);
                drawTextAt(MARGIN_LEFT, y, line, font, fontSize);
                y -= lineHeight;
            }
        }

        void writeBullet(String text, float fontSize, float lineHeight) throws IOException {
            float bulletX = MARGIN_LEFT + 8f;
            float textX = MARGIN_LEFT + 18f;
            List<String> lines = wrapText(text, FONT_REGULAR, fontSize, PAGE_WIDTH - MARGIN_RIGHT - textX);
            if (lines.isEmpty()) {
                return;
            }
            for (int i = 0; i < lines.size(); i++) {
                ensureSpace(lineHeight, null);
                if (i == 0) {
                    drawTextAt(bulletX, y, "-", FONT_REGULAR, fontSize);
                }
                drawTextAt(textX, y, lines.get(i), FONT_REGULAR, fontSize);
                y -= lineHeight;
            }
            y -= 2.5f;
        }

        void writeRightAligned(String text, PDType1Font font, float fontSize) throws IOException {
            String safe = sanitizeForWinAnsi(text);
            float width = font.getStringWidth(safe) / 1000f * fontSize;
            drawTextAt(PAGE_WIDTH - MARGIN_RIGHT - width, y, safe, font, fontSize);
            y -= fontSize + 4f;
        }

        void writeTableHeader(float[] colX, String[] headers) throws IOException {
            for (int i = 0; i < headers.length; i++) {
                drawTextAt(colX[i], y, headers[i], FONT_BOLD, 8.5f);
            }
            y -= 4f;
            drawHorizontalLine();
            y -= 11f;
        }

        void drawHorizontalLine() throws IOException {
            drawHorizontalLine(0.5f);
        }

        void drawHorizontalLine(float lineWidth) throws IOException {
            stream.moveTo(MARGIN_LEFT, y);
            stream.lineTo(PAGE_WIDTH - MARGIN_RIGHT, y);
            stream.setLineWidth(lineWidth);
            stream.stroke();
        }

        void drawSignatureLine(float width) throws IOException {
            stream.moveTo(MARGIN_LEFT, y);
            stream.lineTo(MARGIN_LEFT + width, y);
            stream.setLineWidth(0.5f);
            stream.stroke();
        }

        void drawTextAt(float x, float yPos, String text, PDType1Font font, float fontSize) throws IOException {
            stream.beginText();
            stream.setFont(font, fontSize);
            stream.newLineAtOffset(x, yPos);
            stream.showText(sanitizeForWinAnsi(text));
            stream.endText();
        }

        void finish() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
            int totalPages = doc.getNumberOfPages();
            for (int i = 0; i < totalPages; i++) {
                PDPage page = doc.getPage(i);
                try (PDPageContentStream footer = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String label = "Seite " + (i + 1) + " von " + totalPages;
                    float width = FONT_REGULAR.getStringWidth(label) / 1000f * 8f;
                    footer.beginText();
                    footer.setFont(FONT_REGULAR, 8f);
                    footer.newLineAtOffset(PAGE_WIDTH - MARGIN_RIGHT - width, MARGIN_BOTTOM - 22f);
                    footer.showText(label);
                    footer.endText();
                }
            }
        }
    }
}
