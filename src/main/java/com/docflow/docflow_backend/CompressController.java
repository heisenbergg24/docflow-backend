package com.docflow.docflow_backend;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

@RestController
public class CompressController {

    // Cache the Ghostscript availability check so we don't re-check on every request.
    private static Boolean ghostscriptAvailable = null;

    @PostMapping("/api/compress/image")
    public ResponseEntity<?> compressImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "quality", defaultValue = "0.5") float quality
    ) throws Exception {

        BufferedImage originalImage = ImageIO.read(file.getInputStream());

        if (originalImage == null) {
            return ResponseEntity.status(415)
                    .body("Unsupported image format. Please convert your image to JPG or PNG before uploading. " +
                            "(iPhones often save photos as HEIC — convert first in your Photos app)");
        }

        byte[] compressedBytes = compressToJpeg(originalImage, quality);

        String filename = "compressed_" + file.getOriginalFilename();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.IMAGE_JPEG)
                .body(compressedBytes);
    }

    @PostMapping("/api/compress/pdf")
    public ResponseEntity<?> compressPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "quality", defaultValue = "0.4") float quality
    ) {
        byte[] originalBytes;
        try {
            originalBytes = file.getBytes();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Could not read uploaded file: " + e.getMessage());
        }

        byte[] result = null;

        // Primary path: Ghostscript. This handles font subsetting/deduplication,
        // object stream optimization, AND image downsampling all in one pass -
        // works well regardless of whether the PDF is text-heavy, image-heavy, or mixed.
        if (isGhostscriptAvailable()) {
            try {
                result = compressWithGhostscript(originalBytes, quality);
            } catch (Exception e) {
                System.err.println("Ghostscript compression failed, falling back to PDFBox: " + e.getMessage());
                result = null;
            }
        }

        // Fallback path: PDFBox image-recompression. Used if Ghostscript isn't
        // installed on this machine, or if it failed for some reason.
        if (result == null) {
            try {
                result = compressWithPdfBox(originalBytes, quality);
            } catch (Exception e) {
                e.printStackTrace();
                return ResponseEntity.badRequest().body("Failed to compress PDF: " + e.getMessage());
            }
        }

        // Final safety net: never hand back a "compressed" file that's bigger
        // than the original (can happen on PDFs that are already optimized).
        if (result == null || result.length >= originalBytes.length) {
            result = originalBytes;
        }

        String filename = "compressed_" + file.getOriginalFilename();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(result);
    }

    // ---------------------------------------------------------------------
    // Ghostscript-based compression
    // ---------------------------------------------------------------------

    private boolean isGhostscriptAvailable() {
        if (ghostscriptAvailable != null) {
            return ghostscriptAvailable;
        }
        try {
            Process process = new ProcessBuilder("gs", "--version").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            ghostscriptAvailable = finished && process.exitValue() == 0;
        } catch (Exception e) {
            ghostscriptAvailable = false;
        }
        return ghostscriptAvailable;
    }

    /**
     * Maps our 0.0-1.0 quality slider onto Ghostscript's built-in PDF presets.
     * Lower quality -> more aggressive downsampling/compression (smaller file).
     */
    private String presetForQuality(float quality) {
        if (quality <= 0.3f) return "/screen";   // ~72 DPI images, most aggressive
        if (quality <= 0.6f) return "/ebook";    // ~150 DPI images, balanced
        return "/printer";                       // ~300 DPI images, light compression
    }

    private byte[] compressWithGhostscript(byte[] originalBytes, float quality) throws Exception {
        Path tempDir = Files.createTempDirectory("docflow-compress");
        Path inputPath = tempDir.resolve("input.pdf");
        Path outputPath = tempDir.resolve("output.pdf");

        try {
            Files.write(inputPath, originalBytes);

            String preset = presetForQuality(quality);

            ProcessBuilder builder = new ProcessBuilder(
                    "gs",
                    "-sDEVICE=pdfwrite",
                    "-dCompatibilityLevel=1.4",
                    "-dPDFSETTINGS=" + preset,
                    "-dNOPAUSE",
                    "-dQUIET",
                    "-dBATCH",
                    "-dDetectDuplicateImages=true",
                    "-dCompressFonts=true",
                    "-dSubsetFonts=true",
                    "-sOutputFile=" + outputPath,
                    inputPath.toString()
            );
            builder.redirectErrorStream(true);

            Process process = builder.start();
            // Drain output so Ghostscript never blocks on a full pipe buffer.
            process.getInputStream().readAllBytes();

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Ghostscript timed out");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("Ghostscript exited with code " + process.exitValue());
            }
            if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
                throw new RuntimeException("Ghostscript produced no output");
            }

            return Files.readAllBytes(outputPath);

        } finally {
            try { Files.deleteIfExists(inputPath); } catch (Exception ignored) {}
            try { Files.deleteIfExists(outputPath); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tempDir); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------------
    // PDFBox-based fallback compression (image recompression only)
    // ---------------------------------------------------------------------

    private byte[] compressWithPdfBox(byte[] originalBytes, float quality) throws Exception {
        try (PDDocument document = Loader.loadPDF(originalBytes)) {

            document.setDocumentInformation(new PDDocumentInformation());

            for (PDPage page : document.getPages()) {
                compressImagesInResources(document, page.getResources(), quality);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }

    private void compressImagesInResources(PDDocument document, PDResources resources, float quality) {
        if (resources == null) return;

        for (COSName name : resources.getXObjectNames()) {
            try {
                var xobject = resources.getXObject(name);
                if (!(xobject instanceof PDImageXObject image)) continue;

                BufferedImage bufferedImage;
                try {
                    bufferedImage = image.getImage();
                } catch (Exception decodeError) {
                    System.err.println("Skipping image " + name.getName() + ": " + decodeError.getMessage());
                    continue;
                }

                BufferedImage resizedImage = downscaleIfNeeded(bufferedImage, 1200);
                byte[] compressedBytes = compressToJpeg(resizedImage, quality);

                int originalApproxSize = estimateOriginalSize(image);
                if (compressedBytes.length < originalApproxSize) {
                    PDImageXObject compressedImage = PDImageXObject.createFromByteArray(
                            document, compressedBytes, name.getName()
                    );
                    resources.put(name, compressedImage);
                }
            } catch (Exception unexpected) {
                System.err.println("Unexpected error on image " + name.getName() + ": " + unexpected.getMessage());
            }
        }
    }

    private int estimateOriginalSize(PDImageXObject image) {
        try {
            return (int) image.getCOSObject().getLength();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private BufferedImage downscaleIfNeeded(BufferedImage original, int maxDimension) {
        int width = original.getWidth();
        int height = original.getHeight();

        if (width <= maxDimension && height <= maxDimension) {
            return original;
        }

        double scale = (double) maxDimension / Math.max(width, height);
        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        var g = resized.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, newWidth, newHeight, null);
        g.dispose();

        return resized;
    }

    private BufferedImage toJpegSafe(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgbImage = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB
        );
        var g = rgbImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgbImage;
    }

    private byte[] compressToJpeg(BufferedImage image, float quality) throws Exception {
        BufferedImage safeImage = toJpegSafe(image);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = writers.next();

        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        writer.setOutput(new MemoryCacheImageOutputStream(baos));
        writer.write(null, new IIOImage(safeImage, null, null), param);
        writer.dispose();

        return baos.toByteArray();
    }
}