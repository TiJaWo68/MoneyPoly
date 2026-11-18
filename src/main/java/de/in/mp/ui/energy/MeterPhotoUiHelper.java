package de.in.mp.ui.energy;

import java.io.ByteArrayInputStream;
import java.util.Base64;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AttachmentType;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;

import de.in.mp.energy.MeterReadingPhoto;

/**
 * Shared UI helpers for rendering previews and attachment downloads of archived
 * {@link MeterReadingPhoto} blobs across Energy dialogs.
 */
final class MeterPhotoUiHelper {

    private MeterPhotoUiHelper() {
    }

    static Anchor createDownloadAnchor(Button button, MeterReadingPhoto photo, String downloadFileName) {
        byte[] bytes = photo.getImageData();
        String mimeType = photo.getMimeType() != null ? photo.getMimeType() : "image/jpeg";
        String fileName = (downloadFileName != null && !downloadFileName.isBlank())
                ? downloadFileName
                : (photo.getFileName() != null ? photo.getFileName() : "meter-photo.jpg");

        DownloadHandler handler = DownloadHandler.fromInputStream(
                event -> new DownloadResponse(new ByteArrayInputStream(bytes), fileName, mimeType, bytes.length));
        Anchor anchor = new Anchor(handler, AttachmentType.DOWNLOAD, "");
        anchor.add(button);
        anchor.getStyle().set("text-decoration", "none");
        return anchor;
    }

    static String toDataUri(MeterReadingPhoto photo) {
        String mimeType = photo.getMimeType() != null ? photo.getMimeType() : "image/jpeg";
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(photo.getImageData());
    }
}
