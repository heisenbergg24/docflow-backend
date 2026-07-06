package com.docflow.docflow_backend;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;

@RestController
public class LockUnlockController {

    @PostMapping("/api/lock-pdf")
    public ResponseEntity<?> lockPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("password") String password
    ) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {

            AccessPermission accessPermission = new AccessPermission();

            StandardProtectionPolicy protectionPolicy =
                    new StandardProtectionPolicy(password, password, accessPermission);
            protectionPolicy.setEncryptionKeyLength(128);

            document.protect(protectionPolicy);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);

            String filename = "locked_" + file.getOriginalFilename();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(baos.toByteArray());

        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to lock PDF: " + e.getMessage());
        }
    }

    @PostMapping("/api/unlock-pdf")
    public ResponseEntity<?> unlockPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("password") String password
    ) {
        try (PDDocument document = Loader.loadPDF(file.getBytes(), password)) {

            document.setAllSecurityToBeRemoved(true);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);

            String filename = "unlocked_" + file.getOriginalFilename();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(baos.toByteArray());

        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            return ResponseEntity.status(401).body("Incorrect password");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to unlock PDF: " + e.getMessage());
        }
    }
}