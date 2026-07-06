package com.docflow.docflow_backend;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
public class OrganizeController {

    // ---------------------------------------------------------------------
    // Page count — used by the frontend to build the real page list for
    // Reorder/Delete/Rotate instead of a hardcoded placeholder count.
    // ---------------------------------------------------------------------

    @PostMapping("/api/organize/page-count")
    public ResponseEntity<?> pageCount(@RequestParam("file") MultipartFile file) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return ResponseEntity.ok().body(
                    java.util.Map.of("pageCount", document.getNumberOfPages())
            );
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to read PDF: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Merge — combines multiple PDFs, in the order they're sent, into one.
    // ---------------------------------------------------------------------

    @PostMapping("/api/organize/merge")
    public ResponseEntity<?> merge(@RequestParam("files") List<MultipartFile> files) {
        if (files.size() < 2) {
            return ResponseEntity.badRequest().body("Need at least 2 PDFs to merge.");
        }

        List<PDDocument> openDocs = new ArrayList<>();

        try {
            PDDocument mergedDocument = new PDDocument();
            openDocs.add(mergedDocument);

            for (MultipartFile file : files) {
                PDDocument doc = Loader.loadPDF(file.getBytes());
                openDocs.add(doc); // keep open until after save
                for (PDPage page : doc.getPages()) {
                    mergedDocument.addPage(page);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            mergedDocument.save(baos); // now all source docs are still open

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"merged.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(baos.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to merge PDFs: " + e.getMessage());
        } finally {
            // Close all documents after save is complete
            for (PDDocument doc : openDocs) {
                try { doc.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ---------------------------------------------------------------------
    // Split — either one PDF per page, or one PDF per custom range
    // (e.g. "1-3,5,7-9"), always returned as a zip for consistency.
    // ---------------------------------------------------------------------

    @PostMapping("/api/organize/split")
    public ResponseEntity<?> split(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "all") String mode,
            @RequestParam(value = "ranges", required = false) String ranges
    ) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            String baseName = file.getOriginalFilename().replaceAll("\\.pdf$", "");
            int totalPages = document.getNumberOfPages();

            ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {

                if ("range".equals(mode)) {
                    if (ranges == null || ranges.isBlank()) {
                        return ResponseEntity.badRequest().body("No page ranges provided.");
                    }

                    List<int[]> parsedRanges = parseRanges(ranges, totalPages);
                    if (parsedRanges.isEmpty()) {
                        return ResponseEntity.badRequest().body("Could not parse any valid page ranges.");
                    }

                    int segmentIndex = 1;
                    for (int[] range : parsedRanges) {
                        try (PDDocument segment = new PDDocument()) {
                            for (int p = range[0]; p <= range[1]; p++) {
                                segment.addPage(document.getPage(p - 1));
                            }
                            ByteArrayOutputStream segBaos = new ByteArrayOutputStream();
                            segment.save(segBaos);

                            String label = range[0] == range[1]
                                    ? "page" + range[0]
                                    : "pages" + range[0] + "-" + range[1];

                            ZipEntry entry = new ZipEntry(baseName + "_" + label + ".pdf");
                            zos.putNextEntry(entry);
                            zos.write(segBaos.toByteArray());
                            zos.closeEntry();
                        }
                        segmentIndex++;
                    }

                } else {
                    // "all" mode: one PDF per page
                    for (int i = 0; i < totalPages; i++) {
                        try (PDDocument singlePage = new PDDocument()) {
                            singlePage.addPage(document.getPage(i));
                            ByteArrayOutputStream pageBaos = new ByteArrayOutputStream();
                            singlePage.save(pageBaos);

                            ZipEntry entry = new ZipEntry(baseName + "_page" + (i + 1) + ".pdf");
                            zos.putNextEntry(entry);
                            zos.write(pageBaos.toByteArray());
                            zos.closeEntry();
                        }
                    }
                }
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + baseName + "_split.zip\"")
                    .contentType(MediaType.valueOf("application/zip"))
                    .body(zipBaos.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to split PDF: " + e.getMessage());
        }
    }

    /**
     * Parses a string like "1-3,5,7-9" into a list of [start, end] pairs.
     * Single page numbers become [n, n]. Invalid/out-of-range entries are skipped.
     */
    private List<int[]> parseRanges(String ranges, int totalPages) {
        List<int[]> result = new ArrayList<>();
        String[] parts = ranges.split(",");

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            try {
                if (trimmed.contains("-")) {
                    String[] bounds = trimmed.split("-");
                    int start = Integer.parseInt(bounds[0].trim());
                    int end = Integer.parseInt(bounds[1].trim());
                    if (start < 1) start = 1;
                    if (end > totalPages) end = totalPages;
                    if (start <= end) {
                        result.add(new int[]{start, end});
                    }
                } else {
                    int page = Integer.parseInt(trimmed);
                    if (page >= 1 && page <= totalPages) {
                        result.add(new int[]{page, page});
                    }
                }
            } catch (NumberFormatException ignored) {
                // skip malformed entries rather than failing the whole request
            }
        }

        return result;
    }

    // ---------------------------------------------------------------------
    // Reorder + Delete + Rotate, all in one endpoint. The frontend sends:
    //   - "order": comma-separated ORIGINAL page numbers in their NEW
    //     sequence (e.g. "3,1,2" means new page 1 = old page 3, etc).
    //     Omitting a page number from this list deletes that page.
    //   - "rotations": comma-separated rotation degrees (0/90/180/270),
    //     ALIGNED POSITIONALLY with "order" (same length and order).
    // ---------------------------------------------------------------------

    @PostMapping("/api/organize/reorder")
    public ResponseEntity<?> reorder(
            @RequestParam("file") MultipartFile file,
            @RequestParam("order") String order,
            @RequestParam(value = "rotations", required = false) String rotations
    ) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            int totalPages = document.getNumberOfPages();

            String[] orderParts = order.split(",");
            String[] rotationParts = (rotations != null && !rotations.isBlank())
                    ? rotations.split(",")
                    : new String[0];

            if (orderParts.length == 0) {
                return ResponseEntity.badRequest().body("No page order provided.");
            }

            try (PDDocument newDocument = new PDDocument()) {
                for (int i = 0; i < orderParts.length; i++) {
                    int originalPageNum;
                    try {
                        originalPageNum = Integer.parseInt(orderParts[i].trim());
                    } catch (NumberFormatException e) {
                        continue; // skip malformed entries
                    }

                    if (originalPageNum < 1 || originalPageNum > totalPages) {
                        continue; // skip out-of-range page numbers
                    }

                    PDPage page = document.getPage(originalPageNum - 1);
                    newDocument.addPage(page);

                    if (i < rotationParts.length) {
                        try {
                            int rotationDegrees = Integer.parseInt(rotationParts[i].trim());
                            // Normalize to PDFBox's expected 0/90/180/270 values
                            int normalized = ((rotationDegrees % 360) + 360) % 360;
                            page.setRotation(normalized);
                        } catch (NumberFormatException ignored) {
                            // skip malformed rotation entry, leave page rotation unchanged
                        }
                    }
                }

                if (newDocument.getNumberOfPages() == 0) {
                    return ResponseEntity.badRequest().body("Resulting PDF would have no pages.");
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                newDocument.save(baos);

                String baseName = file.getOriginalFilename().replaceAll("\\.pdf$", "");

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + baseName + "_organized.pdf\"")
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(baos.toByteArray());
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to reorder PDF: " + e.getMessage());
        }
    }
}