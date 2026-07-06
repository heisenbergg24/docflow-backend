package com.docflow.docflow_backend;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import java.util.List;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
public class ConvertController {

    // ---------------------------------------------------------------------
    // PDF -> Image
    // ---------------------------------------------------------------------

    @PostMapping("/api/convert/pdf-to-image")
    public ResponseEntity<byte[]> pdfToImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "format", defaultValue = "png") String format
    ) throws Exception {

        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();

            String baseName = file.getOriginalFilename().replaceAll("\\.pdf$", "");

            if (pageCount == 1) {
                BufferedImage image = renderer.renderImageWithDPI(0, 150);
                byte[] imageBytes = imageToBytes(image, format);

                String filename = baseName + "." + format;
                MediaType mediaType = format.equals("png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                        .contentType(mediaType)
                        .body(imageBytes);
            } else {
                ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
                try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {
                    for (int i = 0; i < pageCount; i++) {
                        BufferedImage image = renderer.renderImageWithDPI(i, 150);
                        byte[] imageBytes = imageToBytes(image, format);

                        ZipEntry entry = new ZipEntry(baseName + "_page" + (i + 1) + "." + format);
                        zos.putNextEntry(entry);
                        zos.write(imageBytes);
                        zos.closeEntry();
                    }
                }

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + baseName + "_images.zip\"")
                        .contentType(MediaType.valueOf("application/zip"))
                        .body(zipBaos.toByteArray());
            }
        }
    }

    private byte[] imageToBytes(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return baos.toByteArray();
    }

    // ---------------------------------------------------------------------
    // Image -> PDF
    // ---------------------------------------------------------------------

    @PostMapping("/api/convert/image-to-pdf")
    public ResponseEntity<byte[]> imageToPdf(
            @RequestParam("files") List<MultipartFile> files
    ) throws Exception {

        try (PDDocument document = new PDDocument()) {

            for (MultipartFile file : files) {
                BufferedImage bufferedImage = ImageIO.read(file.getInputStream());

                float maxWidth = PDRectangle.A4.getWidth();
                float maxHeight = PDRectangle.A4.getHeight();

                float imgWidth = bufferedImage.getWidth();
                float imgHeight = bufferedImage.getHeight();

                float scale = Math.min(maxWidth / imgWidth, maxHeight / imgHeight);
                float pageWidth = imgWidth * scale;
                float pageHeight = imgHeight * scale;

                PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
                document.addPage(page);

                ByteArrayOutputStream imgBaos = new ByteArrayOutputStream();
                ImageIO.write(bufferedImage, "png", imgBaos);
                PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imgBaos.toByteArray(), file.getOriginalFilename());

                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.drawImage(pdImage, 0, 0, pageWidth, pageHeight);
                }
            }

            ByteArrayOutputStream pdfBaos = new ByteArrayOutputStream();
            document.save(pdfBaos);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"converted.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdfBaos.toByteArray());
        }
    }

    // ---------------------------------------------------------------------
    // Office (Word/PowerPoint) -> PDF, via LibreOffice headless
    // ---------------------------------------------------------------------

    @PostMapping("/api/convert/office-to-pdf")
    public ResponseEntity<?> officeToPdf(@RequestParam("file") MultipartFile file) {
        try {
            String originalName = file.getOriginalFilename();
            String extension = originalName.substring(originalName.lastIndexOf('.') + 1);

            byte[] pdfBytes = convertWithLibreOffice(file.getBytes(), extension, "pdf");

            String baseName = originalName.substring(0, originalName.lastIndexOf('.'));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + baseName + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdfBytes);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to convert file to PDF: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // PDF -> Word, via pdf2docx (Python). This does real table and layout
    // reconstruction - genuinely close to what commercial tools produce -
    // unlike LibreOffice (which produced unusable multi-megabyte XML) or a
    // plain Java text dump (which loses all table structure). Requires
    // Python 3 + the pdf2docx package installed on the host machine.
    // ---------------------------------------------------------------------

    @PostMapping("/api/convert/pdf-to-word")
    public ResponseEntity<?> pdfToWord(@RequestParam("file") MultipartFile file) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("docflow-pdf2docx");
            Path inputPath = tempDir.resolve("input.pdf");
            Path outputPath = tempDir.resolve("output.docx");

            Files.write(inputPath, file.getBytes());

            ProcessBuilder builder = new ProcessBuilder(
                    "python3",
                    "pdf_to_docx.py",
                    inputPath.toAbsolutePath().toString(),
                    outputPath.toAbsolutePath().toString()
            );
            builder.redirectErrorStream(true);

            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("PDF to Word conversion timed out");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("pdf2docx failed: " + output);
            }
            if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
                throw new RuntimeException("pdf2docx produced no output file. Output: " + output);
            }

            byte[] docxBytes = Files.readAllBytes(outputPath);
            String baseName = file.getOriginalFilename().replaceAll("\\.pdf$", "");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + baseName + ".docx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    .body(docxBytes);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to convert PDF to Word: " + e.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Shells out to LibreOffice headless mode to perform the conversion.
     *
     * IMPORTANT: each call gets its OWN unique user profile directory via
     * -env:UserInstallation. Without this, concurrent or rapid repeated
     * calls can hit a profile lock and LibreOffice fails SILENTLY - exit
     * code 0, but no output file produced. This was the cause of the
     * "LibreOffice produced no output file" error.
     */
    private byte[] convertWithLibreOffice(byte[] inputBytes, String inputExtension, String outputFormat) throws Exception {
        return convertWithLibreOffice(inputBytes, inputExtension, outputFormat, null);
    }

    private byte[] convertWithLibreOffice(byte[] inputBytes, String inputExtension, String outputFormat, String inFilter) throws Exception {
        Path tempDir = Files.createTempDirectory("docflow-convert");
        Path inputPath = tempDir.resolve("input." + inputExtension);
        Path profileDir = tempDir.resolve("profile-" + UUID.randomUUID());

        try {
            Files.write(inputPath, inputBytes);

            java.util.List<String> command = new java.util.ArrayList<>(List.of(
                    "soffice",
                    "--headless",
                    "--norestore",
                    "-env:UserInstallation=file://" + profileDir.toAbsolutePath()
            ));

            if (inFilter != null) {
                // Forces LibreOffice to import the file using a specific filter -
                // needed for PDF -> Word, since LibreOffice otherwise opens PDFs
                // in Draw (which has no Word export filter at all).
                command.add("--infilter=" + inFilter);
            }

            command.add("--convert-to");
            command.add(outputFormat);
            command.add("--outdir");
            command.add(tempDir.toString());
            command.add(inputPath.toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);

            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("LibreOffice conversion timed out");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("LibreOffice exited with code " + process.exitValue() + ". Output: " + output);
            }

            Path outputPath = tempDir.resolve("input." + outputFormat);
            if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
                throw new RuntimeException("LibreOffice produced no output file. LibreOffice said: " + output);
            }

            return Files.readAllBytes(outputPath);

        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------------
    // PDF -> PowerPoint: one rendered page-image per slide.
    // ---------------------------------------------------------------------

    @PostMapping("/api/convert/pdf-to-ppt")
    public ResponseEntity<?> pdfToPpt(@RequestParam("file") MultipartFile file) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();

            try (XMLSlideShow ppt = new XMLSlideShow()) {

                BufferedImage firstPage = renderer.renderImageWithDPI(0, 120);
                double aspect = (double) firstPage.getWidth() / firstPage.getHeight();
                int slideWidthPoints = 720;
                int slideHeightPoints = (int) (slideWidthPoints / aspect);
                ppt.setPageSize(new java.awt.Dimension(slideWidthPoints, slideHeightPoints));

                for (int i = 0; i < pageCount; i++) {
                    BufferedImage pageImage = renderer.renderImageWithDPI(i, 120);
                    ByteArrayOutputStream imgBaos = new ByteArrayOutputStream();
                    ImageIO.write(pageImage, "png", imgBaos);

                    XSLFPictureData pictureData = ppt.addPicture(imgBaos.toByteArray(), org.apache.poi.sl.usermodel.PictureData.PictureType.PNG);
                    XSLFSlide slide = ppt.createSlide();
                    XSLFPictureShape picture = slide.createPicture(pictureData);

                    java.awt.Dimension pageSize = ppt.getPageSize();
                    picture.setAnchor(new java.awt.Rectangle(0, 0, pageSize.width, pageSize.height));
                }

                ByteArrayOutputStream pptBaos = new ByteArrayOutputStream();
                ppt.write(pptBaos);

                String baseName = file.getOriginalFilename().replaceAll("\\.pdf$", "");

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + baseName + ".pptx\"")
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                        .body(pptBaos.toByteArray());
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to convert PDF to PowerPoint: " + e.getMessage());
        }
    }
}