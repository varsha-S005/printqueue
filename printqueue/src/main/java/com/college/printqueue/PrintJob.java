package com.college.printqueue;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class PrintJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String studentName;
    private String documentName;
    private int pages;
    private int copies;
    private String status;
    private LocalDateTime createdAt;

    // Unique name of the uploaded PDF stored on the server
    private String storedFileName;

    public PrintJob() {
    }

    @PrePersist
    public void beforeSave() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }

        if (status == null) {
            status = "WAITING";
        }
    }

    public Long getId() {
        return id;
    }

    public String getStudentName() {
        return studentName;
    }

    public String getDocumentName() {
        return documentName;
    }

    public int getPages() {
        return pages;
    }

    public int getCopies() {
        return copies;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getStoredFileName() {
        return storedFileName;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public void setPages(int pages) {
        this.pages = pages;
    }

    public void setCopies(int copies) {
        this.copies = copies;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setStoredFileName(String storedFileName) {
        this.storedFileName = storedFileName;
    }
}