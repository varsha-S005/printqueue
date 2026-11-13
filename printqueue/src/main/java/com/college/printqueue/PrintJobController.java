package com.college.printqueue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin
public class PrintJobController {

    private final PrintJobRepository repository;

    @Value("${printqueue.upload-dir:uploads}")
    private String uploadDir;

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    public PrintJobController(PrintJobRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PrintJob> getJobs() {
        return repository.findAllByOrderByIdAsc();
    }

    // Existing JSON submission endpoint
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PrintJob addJob(@RequestBody PrintJob job) {

        validateJob(
                job.getStudentName(),
                job.getDocumentName(),
                job.getPages(),
                job.getCopies()
        );

        job.setId(null);
        job.setStatus("WAITING");
        job.setCreatedAt(null);
        job.setStoredFileName(null);

        return repository.save(job);
    }

    // PDF upload endpoint
    @PostMapping(
        value = "/upload",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public PrintJob uploadJob(
            @RequestParam String studentName,
            @RequestParam String documentName,
            @RequestParam int pages,
            @RequestParam int copies,
            @RequestParam("file") MultipartFile file) {

        validateJob(studentName, documentName, pages, copies);

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Please select a PDF file");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "PDF must be 10 MB or smaller");
        }

        String originalName = file.getOriginalFilename();

        if (originalName == null ||
                !originalName.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Only PDF files are allowed");
        }

        try {
            byte[] content = file.getBytes();

            // Check the PDF file signature
            if (content.length < 5 ||
                    !new String(content, 0, 5, StandardCharsets.US_ASCII)
                            .equals("%PDF-")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid PDF file");
            }

            String storedName = UUID.randomUUID() + ".pdf";

            Path directory = Paths.get(uploadDir)
                    .toAbsolutePath().normalize();

            Files.createDirectories(directory);

            Path destination = directory.resolve(storedName).normalize();

            if (!destination.startsWith(directory)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid file path");
            }

            Files.write(destination, content);

            PrintJob job = new PrintJob();
            job.setStudentName(studentName.trim());
            job.setDocumentName(documentName.trim());
            job.setPages(pages);
            job.setCopies(copies);
            job.setStatus("WAITING");
            job.setStoredFileName(storedName);

            try {
                return repository.save(job);
            } catch (RuntimeException ex) {
                Files.deleteIfExists(destination);
                throw ex;
            }

        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save the uploaded PDF");
        }
    }

    // Download the PDF belonging to a job
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id) {

        PrintJob job = getJobOrThrow(id);

        if (job.getStoredFileName() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No PDF attached to this job");
        }

        try {
            Path directory = Paths.get(uploadDir)
                    .toAbsolutePath().normalize();

            Path filePath = directory
                    .resolve(job.getStoredFileName()).normalize();

            if (!filePath.startsWith(directory) ||
                    !Files.isRegularFile(filePath)) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "PDF file not found");
            }

            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "PDF file is unavailable");
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(job.getDocumentName())
                                .build().toString()
                    )
                    .body(resource);

        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not download the PDF");
        }
    }

    @PutMapping("/{id}/start")
    public PrintJob startJob(@PathVariable Long id) {

        PrintJob job = getJobOrThrow(id);

        boolean anotherPrinting = repository.findAll().stream()
                .anyMatch(j -> "PRINTING".equals(j.getStatus()));

        if (!"WAITING".equals(job.getStatus()) || anotherPrinting) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Finish the current job first");
        }

        // FIFO rule: only the first waiting job can start.
        PrintJob firstWaiting = repository.findAllByOrderByIdAsc().stream()
                .filter(j -> "WAITING".equals(j.getStatus()))
                .findFirst().orElse(null);

        if (firstWaiting == null || !firstWaiting.getId().equals(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Please start the first job in the queue");
        }

        job.setStatus("PRINTING");
        return repository.save(job);
    }

    @PutMapping("/{id}/complete")
    public PrintJob completeJob(@PathVariable Long id) {

        PrintJob job = getJobOrThrow(id);

        if (!"PRINTING".equals(job.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only a printing job can be completed");
        }

        job.setStatus("COMPLETED");
        return repository.save(job);
    }

    // NEW: remove a job (and its PDF file) from the queue
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteJob(@PathVariable Long id) {

        PrintJob job = getJobOrThrow(id);

        if ("PRINTING".equals(job.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot remove a job that is printing");
        }

        // Delete the stored PDF file, if there is one
        if (job.getStoredFileName() != null) {
            try {
                Path directory = Paths.get(uploadDir)
                        .toAbsolutePath().normalize();
                Path filePath = directory
                        .resolve(job.getStoredFileName()).normalize();
                if (filePath.startsWith(directory)) {
                    Files.deleteIfExists(filePath);
                }
            } catch (IOException ex) {
                // Ignore: the job is still removed from the queue
            }
        }

        repository.delete(job);
    }

    private void validateJob(
            String studentName,
            String documentName,
            int pages,
            int copies) {

        if (studentName == null || studentName.isBlank()
                || documentName == null || documentName.isBlank()
                || pages < 1 || pages > 500
                || copies < 1 || copies > 20) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Enter valid print request details");
        }
    }

    private PrintJob getJobOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Job not found"));
    }
}