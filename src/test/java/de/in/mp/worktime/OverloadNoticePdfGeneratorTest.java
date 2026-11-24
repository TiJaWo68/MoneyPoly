package de.in.mp.worktime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import de.in.mp.worktime.OverloadNoticePdfGenerator.OverloadNoticeOptions;
import de.in.mp.worktime.WorkTimeSummary.DaySummary;
import de.in.mp.worktime.WorkTimeSummary.PeriodSummary;

class OverloadNoticePdfGeneratorTest {

    private final OverloadNoticePdfGenerator generator = new OverloadNoticePdfGenerator();

    @Test
    void generatesValidMultiPageOverloadNoticePdfWithChronologicalTable() throws Exception {
        WorkTimeProfile profile = new WorkTimeProfile();
        profile.setId(UUID.randomUUID());
        profile.setFullTimeWeeklyHours(new BigDecimal("40.00"));
        profile.setFullTimeTeachingUnits(new BigDecimal("27.00"));
        profile.setContractTeachingUnits(new BigDecimal("20.00"));
        profile.setWorkDaysPerWeek(5);
        profile.setDailyWarningHours(new BigDecimal("10.00"));
        profile.setMinRestHours(new BigDecimal("11.00"));

        WorkTimeEntry mondayBlock = new WorkTimeEntry();
        mondayBlock.setWorkDate(LocalDate.of(2026, 11, 23));
        mondayBlock.setStartTime(LocalTime.of(6, 45));
        mondayBlock.setEndTime(LocalTime.of(17, 15));
        mondayBlock.setCategory(WorkTimeCategory.SCHOOL);
        mondayBlock.setCountsAsWork(true);
        mondayBlock.setNote("Unterricht, Aufsicht & Konferenz");

        DaySummary mon = new DaySummary(
                LocalDate.of(2026, 11, 23), List.of(mondayBlock),
                705L, 356L, 349L, 60L, true, false, null, false);
        DaySummary sun = new DaySummary(
                LocalDate.of(2026, 11, 22), List.of(),
                225L, 0L, 225L, 0L, false, false, null, true);

        PeriodSummary summary = new PeriodSummary(
                profile.getId(),
                LocalDate.of(2026, 11, 20),
                LocalDate.of(2026, 11, 24),
                2325L,
                1068L,
                1257L,
                1500L,
                600L,
                225L,
                0L,
                180L,
                570L,
                1,
                0,
                List.of(mon, sun));

        byte[] pdfBytes = generator.generate(profile, summary, new OverloadNoticeOptions(
                "Birgit Woitendorf",
                "Gymnasium Schwaan",
                "An die Schulleitung",
                "Personalrat",
                "Bitte um Entlastung bei Zusatzaufgaben.",
                LocalDate.of(2026, 11, 24)));

        assertThat(pdfBytes).isNotEmpty();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            PDFTextStripper page1Stripper = new PDFTextStripper();
            page1Stripper.setStartPage(1);
            page1Stripper.setEndPage(1);
            assertThat(page1Stripper.getText(document)).contains("Mit freundlichen Grüßen");

            String extracted = new PDFTextStripper().getText(document);
            assertThat(extracted).contains("Überlastungsanzeige");
            assertThat(extracted).contains("Birgit Woitendorf");
            assertThat(extracted).contains("20 von 27 Unterrichtsstunden");
            assertThat(extracted).contains("38h 45m");
            assertThat(extracted).contains("+20h 57m");
            assertThat(extracted).contains("1 Tag mit mehr als 10 Stunden Arbeitszeit");
            assertThat(extracted).doesNotContain("Unterschreitung");
            assertThat(extracted).contains("Anlage: Dokumentierte Arbeitszeiten");
            assertThat(extracted).contains("23.11.2026");
            assertThat(extracted.replaceAll("\\s+", " ")).contains("Unterricht, Aufsicht & Konferenz");
        }
    }
}
